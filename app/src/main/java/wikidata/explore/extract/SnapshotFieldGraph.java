package wikidata.explore.extract;

import objectview.field.FieldKind;
import objectview.viewconfig.FieldTypeSource;
import objectview.Viewable;
import quiz.transform.ui.DomainField;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Versioned, persisted semantic shape of a snapshot.
 *
 * <p>The graph is accumulated while the snapshot writer already walks every reachable
 * object. Consequently opening a saved domain does not have to rediscover its schema
 * by scanning tens or hundreds of thousands of instances. Inline VALUE objects are
 * ordinary graph nodes; their value/entity distinction is metadata and never changes
 * field traversal or rendering.
 */
public final class SnapshotFieldGraph {

    public static final int FORMAT_VERSION = 1;
    private static final int MAX_PATH_DEPTH = 6;

    public int version = FORMAT_VERSION;
    public Map<String, TypeShape> types = new LinkedHashMap<>();

    public SnapshotFieldGraph() {}

    /** Backward-compatible derivation for a snapshot written before the graph existed. */
    public static SnapshotFieldGraph derive(
            Collection<WikidataDynamicObject> roots) {
        Builder builder = builder();
        Set<WikidataDynamicObject> seen =
                Collections.newSetFromMap(new IdentityHashMap<>());
        if (roots != null) {
            for (WikidataDynamicObject root : roots) {
                derive(root, builder, seen);
            }
        }
        return builder.build();
    }

    private static void derive(Object value, Builder builder,
                               Set<WikidataDynamicObject> seen) {
        if (value instanceof WikidataDynamicObject object) {
            if (!seen.add(object)) {
                return;
            }
            builder.observe(object);
            for (Object nested : object.dynamicFieldValues().values()) {
                derive(nested, builder, seen);
            }
        } else if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                derive(item, builder, seen);
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                derive(item, builder, seen);
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<String> memberTypes() {
        List<String> result = new ArrayList<>();
        for (TypeShape type : types.values()) {
            if (type.member && hasSubstance(type)) {
                result.add(type.name);
            }
        }
        return result;
    }

    private static boolean hasSubstance(TypeShape type) {
        return !type.fields.isEmpty()
                && !(type.fields.size() == 1
                && type.fields.containsKey("wikidata"));
    }

    public List<DomainField> fields(String topType,
                                    Set<String> structuralTopFields) {
        List<DomainField> result = new ArrayList<>();
        collectFields(topType, topType, "", new LinkedHashSet<>(), 0,
                structuralTopFields == null ? Set.of() : structuralTopFields,
                result);
        return result;
    }

    private void collectFields(String topType, String currentType, String prefix,
                               Set<String> chain, int depth,
                               Set<String> structuralTopFields,
                               List<DomainField> result) {
        TypeShape type = types.get(currentType);
        if (type == null || depth > MAX_PATH_DEPTH || !chain.add(currentType)) {
            return;
        }
        for (FieldShape field : type.fields.values()) {
            if ((depth == 0 && structuralTopFields.contains(field.name))
                    || field.structural) {
                continue;
            }
            String path = prefix.isEmpty()
                    ? field.name : prefix + "." + field.name;
            result.add(new DomainField(topType, path, field.reference,
                    field.collection, field.domainKind()));

            String target = field.primaryTargetType();
            if (field.reference && target != null) {
                result.add(new DomainField(topType, path + ".name",
                        false, false, FieldKind.TEXT));
                collectFields(topType, target, path,
                        new LinkedHashSet<>(chain), depth + 1,
                        Set.of(), result);
            }
        }
        chain.remove(currentType);
    }

    public FieldTypeSource fieldTypes(String typeName) {
        return new GraphFieldTypeSource(this, typeName);
    }

    /**
     * Small shape-bearing object tree for the existing dynamic field editor API.
     * Values are placeholders; all authoritative labels and nesting come from
     * {@link #fieldTypes(String)}.
     */
    public WikidataDynamicObject shapeSample(String typeName) {
        return shapeSample(typeName, new LinkedHashSet<>(), 0);
    }

    private WikidataDynamicObject shapeSample(String typeName,
                                               Set<String> chain,
                                               int depth) {
        TypeShape type = types.get(typeName);
        if (type == null) {
            return null;
        }
        WikidataDynamicObject sample =
                new WikidataDynamicObject("__shape__:" + typeName, typeName);
        sample.type(typeName);
        sample.valueObject(type.valueObject);
        if (depth > MAX_PATH_DEPTH || !chain.add(typeName)) {
            return sample;
        }
        for (FieldShape field : type.fields.values()) {
            Object value;
            String target = field.primaryTargetType();
            if (field.reference && target != null) {
                value = shapeSample(target, new LinkedHashSet<>(chain), depth + 1);
            } else {
                value = field.placeholder();
            }
            if (field.collection) {
                value = value == null ? List.of() : List.of(value);
            }
            if (value != null) {
                sample.put(field.name, value);
            }
        }
        chain.remove(typeName);
        return sample;
    }

    public static final class Builder {
        private final SnapshotFieldGraph graph = new SnapshotFieldGraph();

        /**
         * Observe one object only. The snapshot writer invokes this from its existing
         * reachability walk, so graph construction adds no second instance traversal.
         */
        public void observe(WikidataDynamicObject object) {
            if (object == null || !object.hasTypeStamp()
                    || object.typeName() == null
                    || object.typeName().isBlank()) {
                return;
            }
            TypeShape type = graph.types.computeIfAbsent(
                    object.typeName(), TypeShape::new);
            type.member |= !object.isValueObject();
            type.valueObject |= object.isValueObject();
            for (Map.Entry<String, Object> entry
                    : object.dynamicFieldValues().entrySet()) {
                type.fields.computeIfAbsent(entry.getKey(), FieldShape::new)
                        .observe(entry.getValue());
            }
        }

        public SnapshotFieldGraph build() {
            return graph;
        }
    }

    public static final class TypeShape {
        public String name;
        public boolean member;
        public boolean valueObject;
        public Map<String, FieldShape> fields = new LinkedHashMap<>();

        public TypeShape() {}

        TypeShape(String name) {
            this.name = name;
        }
    }

    public static final class FieldShape {
        public String name;
        public boolean scalarObserved;
        public boolean collection;
        public boolean reference;
        public boolean structural;
        public String scalarKind = FieldKind.UNKNOWN.name();
        public String scalarTypeLabel = "";
        public List<String> targetTypes = new ArrayList<>();

        public FieldShape() {}

        FieldShape(String name) {
            this.name = name;
        }

        void observe(Object value) {
            if (value instanceof Collection<?> collectionValue) {
                collection = true;
                for (Object item : collectionValue) {
                    observeAtom(item);
                }
            } else {
                scalarObserved = true;
                observeAtom(value);
            }
        }

        private void observeAtom(Object value) {
            if (value == null) {
                return;
            }
            if (value instanceof WikidataDynamicObject dynamic
                    && !dynamic.hasTypeStamp()
                    && (dynamic.dynamicFieldValues().isEmpty()
                    || (dynamic.dynamicFieldValues().size() == 1
                    && dynamic.dynamicFieldValues().containsKey("wikidata")))) {
                observeAtom(dynamic.getDisplayName());
                return;
            }
            if (value instanceof Viewable viewable) {
                reference = true;
                String target = viewable.typeName();
                if (target != null && !target.isBlank()
                        && !"WikidataDynamicObject".equals(target)
                        && !targetTypes.contains(target)) {
                    targetTypes.add(target);
                }
                return;
            }
            FieldKind observed = FieldKind.ofValue(value);
            FieldKind previous = scalarKind();
            if (previous == FieldKind.UNKNOWN) {
                scalarKind = observed.name();
                scalarTypeLabel = value.getClass().getSimpleName();
            } else if (previous != observed) {
                scalarKind = FieldKind.UNKNOWN.name();
                scalarTypeLabel = "Object";
            }
        }

        public String primaryTargetType() {
            return targetTypes.isEmpty() ? null : targetTypes.get(0);
        }

        public FieldKind scalarKind() {
            try {
                return FieldKind.valueOf(scalarKind);
            } catch (Exception ignored) {
                return FieldKind.UNKNOWN;
            }
        }

        public FieldKind domainKind() {
            return collection ? FieldKind.COLLECTION
                    : reference ? FieldKind.REFERENCE : scalarKind();
        }

        public String typeLabel() {
            String element = reference && primaryTargetType() != null
                    ? primaryTargetType()
                    : scalarTypeLabel == null || scalarTypeLabel.isBlank()
                    ? "Object" : scalarTypeLabel;
            return collection ? "Collection<" + element + ">" : element;
        }

        Object placeholder() {
            return switch (scalarKind()) {
                case BOOLEAN -> false;
                case ORDERED -> 0;
                case MEDIA -> new WikidataMediaValue();
                default -> "";
            };
        }
    }

    private record GraphFieldTypeSource(
            SnapshotFieldGraph graph, String typeName) implements FieldTypeSource {

        @Override public FieldTypeInfo field(String name) {
            TypeShape type = graph.types.get(typeName);
            FieldShape field = type == null ? null : type.fields.get(name);
            if (field == null) {
                return null;
            }
            String target = field.primaryTargetType();
            FieldTypeSource nested = target != null
                    && graph.types.containsKey(target)
                    && !graph.types.get(target).fields.isEmpty()
                    ? new GraphFieldTypeSource(graph, target) : null;
            return new FieldTypeInfo(field.typeLabel(), field.structural,
                    nested == null ? null : target, nested);
        }

        @Override public List<String> fieldNames() {
            TypeShape type = graph.types.get(typeName);
            return type == null ? List.of()
                    : List.copyOf(type.fields.keySet());
        }
    }
}
