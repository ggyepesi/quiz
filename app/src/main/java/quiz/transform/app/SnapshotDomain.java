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
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A {@link DomainModel} over a loaded Wikidata snapshot pool (the wikidata bridge). */
public final class SnapshotDomain implements DomainModel {

    private final List<WikidataDynamicObject> pool;
    private final DomainSchema schema;
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
        for (String field : schema.fields(type)) {
            Object value = firstNonNull(type, field);
            if (value != null) {
                merged.put(field, representative(value, visiting));
            }
        }
        visiting.remove(type);
        return merged;
    }

    private Object firstNonNull(String type, String field) {
        for (WikidataDynamicObject o : pool) {
            if (o != null && type.equals(o.typeName())) {
                Object v = o.get(field);
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }

    /** Replace a reference value with a union sample of its type so nested fields are
     *  complete too; leave scalars and non-reference collections as the representative. */
    private Object representative(Object value, Set<String> visiting) {
        if (value instanceof WikidataDynamicObject ref && ref.typeName() != null) {
            return buildUnion(ref.typeName(), visiting);
        }
        if (value instanceof Collection<?> c) {
            for (Object item : c) {
                if (item instanceof WikidataDynamicObject ref && ref.typeName() != null) {
                    List<Object> out = new ArrayList<>();
                    out.add(buildUnion(ref.typeName(), visiting));
                    return out;
                }
            }
        }
        return value;
    }

    @Override public Collection<? extends Quizable> instances() { return pool; }
    @Override public Class<? extends Quizable> universe() { return WikidataDynamicObject.class; }
}
