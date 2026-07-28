package quiz.transform.app;

import objectview.field.ViewableFieldPaths;
import quiz.Quizable;
import objectview.field.FieldAccess;
import quiz.transform.ui.DomainField;
import quiz.transform.ui.DomainModel;
import objectview.field.FieldKind;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A {@link DomainModel} over a loaded Wikidata snapshot pool (the wikidata bridge). */
public final class SnapshotDomain implements DomainModel {

    private final List<WikidataDynamicObject> pool;
    private final DomainSchema schema;
    // Includes nested inline VALUE objects as well as root pool members. A recursive
    // union cannot find VALUE fields by scanning the root pool because those objects
    // are deliberately serialized in their owner rather than pooled.
    private final Map<String, List<WikidataDynamicObject>> objectsByType =
            new java.util.LinkedHashMap<>();
    // Statement-reification classes (from the domain's model): their "source"
    // field is the auto-created reify back-reference — provenance, not an
    // argument — so the field pickers skip it.
    private final java.util.Set<String> statementTypes;

    public SnapshotDomain(List<WikidataDynamicObject> pool) {
        this(pool, java.util.Set.of());
    }

    public SnapshotDomain(List<WikidataDynamicObject> pool,
                          java.util.Set<String> statementTypes) {
        // A bare reference (unstamped, no substance — e.g. the type values
        // "film", "song") reads as its display-name String, not an object chip.
        wikidata.explore.transform.BareReferenceCollapse.apply(pool);
        this.pool = pool;
        this.schema = new DomainSchema(pool);
        Set<WikidataDynamicObject> seen =
                java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (WikidataDynamicObject object : pool) {
            indexReachable(object, seen);
        }
        this.statementTypes = statementTypes == null
                ? java.util.Set.of() : statementTypes;
    }

    @Override public List<String> types() { return schema.types(); }

    @Override public java.util.Set<String> structuralFields(String type) {
        // The wikidata convention, translated to the generic seam: a statement
        // class's "source" field is the reify back-reference (provenance).
        return statementTypes.contains(type)
                ? java.util.Set.of("source") : java.util.Set.of();
    }

    @Override public List<DomainField> fields(String type) {
        // Enumerate from a UNION sample so NESTED paths (nominee.name, category.edition)
        // appear AND no field is missed because an arbitrary first instance lacked it
        // (e.g. a laureate with no portrait). Shape is read from the union sample.
        WikidataDynamicObject sample = unionSample(type);
        if (sample == null || sample.dynamicFieldValues().isEmpty()) {
            return List.of();
        }
        java.util.Set<String> structural = structuralFields(type);
        List<DomainField> out = new ArrayList<>();
        for (ViewableFieldPaths.FieldPath fp
                : ViewableFieldPaths.collectFromSample(sample, ViewableFieldPaths.ALL_FIELDS)) {
            String head = fp.dotted().contains(".")
                    ? fp.dotted().substring(0, fp.dotted().indexOf('.'))
                    : fp.dotted();
            if (structural.contains(head)) {
                continue;
            }
            Object value = FieldAccess.getPath(sample, fp.dotted());
            boolean ref = value instanceof Quizable
                    || (value instanceof Collection<?> c && anyQuizable(c));
            boolean col = value instanceof Collection<?>;
            FieldKind kind = col ? FieldKind.COLLECTION
                    : ref ? FieldKind.REFERENCE : FieldKind.ofValue(value);
            out.add(new DomainField(type, fp, ref, col, kind));
        }
        return out;
    }

    private static boolean anyQuizable(Collection<?> c) {
        for (Object o : c) {
            if (o instanceof Quizable) return true;
        }
        return false;
    }

    private final Map<String, WikidataDynamicObject> unionSamples = new HashMap<>();

    /** A UNION sample stands in for the type when enumerating fields, so every field
     *  shows regardless of which single instance is inspected. */
    @Override public Quizable representativeSample(String type) {
        return unionSample(type);
    }

    /** A synthetic instance whose fields are the UNION across every instance of {@code
     *  type} — the first non-null value per field (references replaced by a union sample
     *  of their own type, so nested enumeration is complete too). Not pooled; used only
     *  to enumerate the complete field set. Cached. */
    private WikidataDynamicObject unionSample(String type) {
        return unionSamples.computeIfAbsent(type, t -> buildUnion(t, new HashSet<>()));
    }

    private WikidataDynamicObject buildUnion(String type, Set<String> visiting) {
        WikidataDynamicObject merged = new WikidataDynamicObject(type, type);
        merged.type(type);
        if (!visiting.add(type)) {
            return merged;   // cycle guard: a self-referential type stops here
        }
        for (String field : fieldsOf(type)) {
            Object value = representativeValue(type, field, visiting);
            if (value != null) {
                merged.put(field, value);
            }
        }
        visiting.remove(type);
        return merged;
    }

    private Set<String> fieldsOf(String type) {
        Set<String> fields = new LinkedHashSet<>(schema.fields(type));
        for (WikidataDynamicObject object
                : objectsByType.getOrDefault(type, List.of())) {
            fields.addAll(object.dynamicFieldValues().keySet());
        }
        return fields;
    }

    /** Pick a shape-bearing representative across every occurrence of the field.
     *  Empty collections do not mask a populated collection on a later object. */
    private Object representativeValue(String type, String field, Set<String> visiting) {
        List<Object> values = new ArrayList<>();
        for (WikidataDynamicObject object
                : objectsByType.getOrDefault(type, List.of())) {
            Object value = object.get(field);
            if (value != null) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            return null;
        }

        boolean collection = values.stream().anyMatch(Collection.class::isInstance);
        List<Object> candidates = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Collection<?> items) {
                candidates.addAll(items);
            } else {
                candidates.add(value);
            }
        }

        Object representative = representative(candidates, visiting);
        if (collection) {
            return representative == null ? List.of() : List.of(representative);
        }
        return representative;
    }

    /** Replace a reference value with the recursively indexed union sample of its type;
     *  otherwise retain the first real scalar as the representative shape. */
    private Object representative(List<Object> candidates, Set<String> visiting) {
        for (Object candidate : candidates) {
            if (candidate instanceof WikidataDynamicObject ref
                    && ref.hasTypeStamp() && ref.typeName() != null) {
                return buildUnion(ref.typeName(), visiting);
            }
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private void indexReachable(
            Object value, Set<WikidataDynamicObject> seen) {
        if (value instanceof WikidataDynamicObject object) {
            if (!seen.add(object)) {
                return;
            }
            if (object.hasTypeStamp() && object.typeName() != null) {
                objectsByType.computeIfAbsent(object.typeName(), ignored -> new ArrayList<>())
                        .add(object);
            }
            for (Object nested : object.dynamicFieldValues().values()) {
                indexReachable(nested, seen);
            }
        } else if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                indexReachable(item, seen);
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                indexReachable(item, seen);
            }
        }
    }

    @Override public Collection<? extends Quizable> instances() { return pool; }
    @Override public Class<? extends Quizable> universe() { return WikidataDynamicObject.class; }
}
