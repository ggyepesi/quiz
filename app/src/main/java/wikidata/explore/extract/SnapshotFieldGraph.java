package wikidata.explore.extract;

import wikidata.explore.extract.WikidataDynamicObject;

import objectview.field.FieldKind;
import objectview.field.FieldRef;
import objectview.field.FieldSchema;
import objectview.Viewable;
import quiz.transform.ui.DomainModel;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

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

    public static final int FORMAT_VERSION = 2;
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

    /** Every class the snapshot actually represents — carried by, or referenced from,
     *  its instances. The CURATABLE set: a field-only class such as {@code Language} is
     *  here (its instances are type-stamped), independent of whether it is served. The
     *  {@code member} bit only distinguishes {@link #memberTypes()} (served on the quiz
     *  web UI) from the referenced classes; it does not gate what exists. */
    public List<String> allTypes() {
        List<String> result = new ArrayList<>();
        for (TypeShape type : types.values()) {
            if (hasSubstance(type)) {
                result.add(type.name);
            }
        }
        return result;
    }

    /** The subset of {@link #allTypes()} served on the quiz web UI — the explicit
     *  member roots supplied at save (see {@code markMembers}). */
    public List<String> memberTypes() {
        List<String> result = new ArrayList<>();
        for (TypeShape type : types.values()) {
            if (type.member && hasSubstance(type)) {
                result.add(type.name);
            }
        }
        return result;
    }

    public String baseType(String typeName) {
        TypeShape type = types.get(typeName);
        return type == null || type.baseType == null || type.baseType.isBlank()
                ? null : type.baseType;
    }

    private static boolean hasSubstance(TypeShape type) {
        return !type.fields.isEmpty()
                && !(type.fields.size() == 1
                && type.fields.containsKey("wikidata"));
    }

    /**
     * Canonical top-level schema reconstructed directly from the persisted field
     * graph. No instance sampling or type-label parsing is needed on load.
     */
    public FieldSchema fieldSchema(
            String typeName, Set<String> extraStructuralFields) {
        TypeShape type = types.get(typeName);
        if (type == null) {
            return null;
        }
        Set<String> extra = extraStructuralFields == null
                ? Set.of() : extraStructuralFields;
        List<FieldRef> refs = new ArrayList<>();
        for (FieldShape field : type.fields.values()) {
            FieldKind valueKind = field.reference
                    ? FieldKind.REFERENCE : field.scalarKind();
            refs.add(FieldRef.described(
                    field.name, field.domainKind(), valueKind,
                    field.typeLabel(), field.reference, field.collection,
                    field.primaryTargetType(),
                    field.structural || extra.contains(field.name),
                    field.minor, field.inline, field.link, field.linkText,
                    field.annotatedReference));
        }
        List<FieldRef> immutable = List.copyOf(refs);
        return () -> immutable;
    }

    /**
     * Small shape-bearing object tree for the existing dynamic field editor API.
     * Values are placeholders; all authoritative labels and nesting come from
     * {@link #fieldSchema(String, Set)}.
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
            java.util.Set<String> classes = object.directClassNames();
            if (classes.isEmpty()) classes = java.util.Set.of(object.typeName());
            for (String className : classes) {
                TypeShape type = graph.types.computeIfAbsent(className, TypeShape::new);
                type.member |= !object.isValueObject();
                type.valueObject |= object.isValueObject();
                for (Map.Entry<String, Object> entry
                        : object.dynamicFieldValues().entrySet()) {
                    type.fields.computeIfAbsent(entry.getKey(), FieldShape::new)
                            .observe(entry.getValue());
                }
            }
        }

        /**
         * Add the producing domain's declared shape after observing its values.
         * Observation remains authoritative for actual cardinality/targets; the
         * declaration fills gaps left by null fields and empty collections.
         */
        public void declare(DomainModel domain) {
            if (domain == null) {
                return;
            }
            for (String typeName : domain.types()) {
                if (typeName == null || typeName.isBlank()) {
                    continue;
                }
                TypeShape type = graph.types.computeIfAbsent(
                        typeName, TypeShape::new);
                type.baseType = domain.baseType(typeName);
                declareType(typeName, domain, new LinkedHashSet<>());
            }
        }

        /**
         * Add the exact ModelBuilder declaration that produced a generated pool.
         * Observed values remain authoritative; this supplies declaration-only shape
         * such as null fields, empty typed collections, inheritance and render metadata.
         */
        public void declare(GeneratedProjectModel model) {
            if (model == null) {
                return;
            }
            Set<String> seen = new LinkedHashSet<>();
            for (GeneratedClassModel generatedClass : model.classes()) {
                if (generatedClass == null
                        || !seen.add(generatedClass.className())) {
                    continue;
                }
                TypeShape type = graph.types.computeIfAbsent(
                        generatedClass.className(), TypeShape::new);
                type.baseType = generatedClass.baseClassName();
                for (GeneratedFieldModel generatedField
                        : generatedClass.effectiveFields(model)) {
                    if (generatedField == null || generatedField.name() == null
                            || generatedField.name().isBlank()) {
                        continue;
                    }
                    FieldRef declared = quiz.transform.ui.FieldDefinitions.toFieldRef(
                            generatedField.definition());
                    type.fields.computeIfAbsent(
                            generatedField.name(), FieldShape::new).declare(declared);
                    if (declared != null && declared.targetType() != null
                            && !declared.targetType().isBlank()) {
                        graph.types.computeIfAbsent(
                                declared.targetType(), TypeShape::new);
                    }
                }
                if (generatedClass.reifiesStatements()) {
                    type.fields.computeIfAbsent("source", FieldShape::new)
                            .declare(FieldRef.withStructural(
                                    FieldRef.of("source", FieldKind.REFERENCE,
                                            "Viewable", true, false, false),
                                    true));
                }
            }
        }

        /** Root membership is domain topology, not reachability. After the full graph
         * has been observed/declared, mark only explicitly supplied member-root types. */
        public void markMembers(
                Collection<WikidataDynamicObject> memberRoots) {
            for (TypeShape type : graph.types.values()) {
                type.member = false;
            }
            if (memberRoots == null) {
                return;
            }
            for (WikidataDynamicObject root : memberRoots) {
                if (root == null || root.isValueObject()
                        || root.typeName() == null || root.typeName().isBlank()) {
                    continue;
                }
                for (String directClass : root.directClassNames()) {
                    markMemberAndBases(directClass);
                }
            }
        }

        private void markMemberAndBases(String className) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            String current = className;
            while (current != null && !current.isBlank() && seen.add(current)) {
                TypeShape type = graph.types.computeIfAbsent(current, TypeShape::new);
                type.member = true;
                current = type.baseType;
            }
        }

        private void declareType(String typeName, DomainModel domain,
                                 Set<String> chain) {
            FieldSchema schema = domain.fieldSchema(typeName);
            if (schema == null || !chain.add(typeName)) {
                return;
            }
            TypeShape type = graph.types.computeIfAbsent(
                    typeName, TypeShape::new);
            type.baseType = domain.baseType(typeName);
            for (FieldRef declared : schema.fields()) {
                if (declared == null || declared.name() == null
                        || declared.name().isBlank()) {
                    continue;
                }
                FieldShape field = type.fields.computeIfAbsent(
                        declared.name(), FieldShape::new);
                field.declare(declared);
                String target = declared.targetType();
                if (!declared.structural() && target != null
                        && !target.isBlank()) {
                    graph.types.computeIfAbsent(
                            target, TypeShape::new);
                    declareType(target, domain,
                            new LinkedHashSet<>(chain));
                }
            }
            chain.remove(typeName);
        }

        public SnapshotFieldGraph build() {
            return graph;
        }
    }

    public static final class TypeShape {
        public String name;
        public boolean member;
        public boolean valueObject;
        public String baseType;
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
        public boolean minor;
        public boolean inline;
        public boolean link;
        public String linkText = "";
        public boolean annotatedReference;
        public String scalarKind = FieldKind.UNKNOWN.name();
        public String scalarTypeLabel = "";
        public List<String> targetTypes = new ArrayList<>();

        public FieldShape() {}

        FieldShape(String name) {
            this.name = name;
        }

        void declare(FieldRef field) {
            if (field == null) {
                return;
            }
            collection |= field.collection();
            reference |= field.reference();
            structural |= field.structural();
            minor |= field.minor();
            inline |= field.inline();
            link |= field.link();
            if (field.linkText() != null && !field.linkText().isBlank()) {
                linkText = field.linkText();
            }
            annotatedReference |= field.annotatedReference();
            String target = field.targetType();
            if (target != null && !target.isBlank()
                    && !targetTypes.contains(target)) {
                targetTypes.add(target);
            }
            if (!field.reference()) {
                declareKind(field.valueKind());
                String label = elementTypeLabel(field.typeLabel() == null
                        ? "" : field.typeLabel().trim());
                if (!label.isBlank()) {
                    // The declared label is more precise than the kind fallback
                    // ("FlexibleDate", not merely "Ordered").
                    scalarTypeLabel = label;
                }
            }
        }

        void declareKind(FieldKind kind) {
            if (kind == null || kind == FieldKind.UNKNOWN
                    || kind == FieldKind.COLLECTION
                    || kind == FieldKind.REFERENCE) {
                return;
            }
            if (scalarKind() == FieldKind.UNKNOWN) {
                scalarKind = kind.name();
            }
            if (scalarTypeLabel == null || scalarTypeLabel.isBlank()) {
                scalarTypeLabel = switch (kind) {
                    case BOOLEAN -> "Boolean";
                    case ORDERED -> "Ordered";
                    case TEXT -> "String";
                    case MEDIA -> "MediaValue";
                    default -> "";
                };
            }
        }

        private static String elementTypeLabel(String label) {
            int open = label.indexOf('<');
            int close = label.lastIndexOf('>');
            if (open < 0 || close <= open) {
                return label;
            }
            String inside = label.substring(open + 1, close).trim();
            // For Map<K,V>, the value is the field's element type.
            int comma = inside.lastIndexOf(',');
            return comma < 0 ? inside : inside.substring(comma + 1).trim();
        }

        void observe(Object value) {
            if (value instanceof Collection<?> collectionValue) {
                collection = true;
                for (Object item : collectionValue) {
                    observeAtom(item);
                }
            } else if (value instanceof Map<?, ?> mapValue) {
                collection = true;
                for (Object item : mapValue.values()) {
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

}
