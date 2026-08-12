package quiz.transform.ui;

import objectview.viewconfig.FieldTypeSource;
import objectview.Viewable;
import objectview.field.FieldSchema;

import java.util.Collection;
import java.util.List;

/**
 * A domain the transform workbench operates over: its instances plus the schema
 * (types and, per type, field names and shapes) the operation slots filter on.
 * Decoupled from any particular backing so the SAME workbench runs over a Wikidata
 * snapshot ({@link SnapshotDomain}) or the hand-written {@code Viewable} domains —
 * Nobel, State, SportTeam — via reflection ({@link ReflectionDomain}).
 */
public interface DomainModel {

    /** Every class represented in the domain — one uniform set derived the same way
     *  for built-in and snapshot backings: the classes carried by (or referenced from)
     *  the domain's instances. Built-in domains discover these by reflection (walking
     *  declared field types); snapshot domains from the type stamps observed on the
     *  pool. This is the CURATABLE set — a field-only class such as {@code Language}
     *  belongs here even though nothing serves it. {@link #servedTypes()} is the subset
     *  shown on the quiz web UI. */
    List<String> types();

    /** The subset of {@link #types()} served on the quiz web UI (the "domain class"
     *  bit). A snapshot serves its explicit member roots; a built-in domain serves the
     *  same classes it exposes by default. Web-facing sources enumerate THIS, never
     *  {@link #types()}, so curation can reach every class without publishing it. */
    default List<String> servedTypes() { return types(); }

    /** Explicit base class, or null for a root class. */
    default String baseType(String type) { return null; }

    /** Whether {@code candidate} is {@code expected} or extends it. */
    default boolean isSubclassOf(String candidate, String expected) {
        if (candidate == null || expected == null) return false;
        java.util.Set<String> seen = new java.util.HashSet<>();
        String current = candidate;
        while (current != null && seen.add(current)) {
            if (expected.equals(current)) return true;
            current = baseType(current);
        }
        return false;
    }

    /** Class membership belongs to the instance; inherited membership is derived
     * from the explicit class hierarchy. */
    default boolean isInstanceOf(Viewable instance, String type) {
        if (instance == null || type == null) return false;
        for (String direct : directClasses(instance)) {
            if (isSubclassOf(direct, type)) return true;
        }
        return false;
    }

    /** Direct claims carried by an instance. A mutable working domain may overlay
     * newly assigned classes until they are materialized into a snapshot. */
    default java.util.Set<String> directClasses(Viewable instance) {
        return instance == null ? java.util.Set.of() : instance.directClassNames();
    }

    /** The deepest direct class claim, used by consumers that need one concrete
     * schema/rendering type while membership itself remains multi-valued. */
    default String mostSpecificClass(Viewable instance) {
        String best = null;
        int bestDepth = -1;
        for (String direct : directClasses(instance)) {
            int depth = 0;
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String current = direct; current != null && seen.add(current);
                 current = baseType(current)) {
                depth++;
            }
            if (depth > bestDepth) {
                best = direct;
                bestDepth = depth;
            }
        }
        return best;
    }

    default List<Viewable> instancesOf(String type) {
        return instances().stream().filter(value -> isInstanceOf(value, type))
                .map(Viewable.class::cast).toList();
    }

    default List<String> subtypesOf(String type) {
        return types().stream().filter(candidate -> !candidate.equals(type)
                && isSubclassOf(candidate, type)).toList();
    }

    /** Fields introduced by this subtype rather than inherited from its base. */
    default java.util.Set<String> additionalFields(String subtype) {
        FieldSchema schema = fieldSchema(subtype);
        String base = baseType(subtype);
        FieldSchema inherited = base == null ? null : fieldSchema(base);
        java.util.Set<String> inheritedNames = new java.util.LinkedHashSet<>();
        if (inherited != null) {
            for (objectview.field.FieldRef field : inherited.fields()) {
                inheritedNames.add(field.name());
            }
        }
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        if (schema != null) {
            for (objectview.field.FieldRef field : schema.fields()) {
                if (!field.structural()
                        && !inheritedNames.contains(field.name())) {
                    result.add(field.name());
                }
            }
        }
        return java.util.Set.copyOf(result);
    }

    /** The fields of a type — possibly NESTED paths (e.g. {@code nominee.name}) —
     *  each carrying its dotted path and leaf shape (reference/collection). */
    default List<DomainField> fields(String type) {
        return DomainSchemas.fields(this, type);
    }

    /** Whether a field originated as an entity-valued declaration, even if compilation
     *  collapsed its runtime value to a display-name String. */
    default boolean entityOrigin(String type, objectview.field.FieldPath path) {
        objectview.field.FieldRef field = DomainSchemas.resolve(this, type, path);
        return field != null && field.reference();
    }

    /**
     * The canonical immutable top-level schema for {@code type} — the single source of
     * truth for field structure (names, shapes, reference targets, structural roles).
     *
     * Every domain supplies this canonical representation. Operation paths,
     * structural fields and field-config metadata are projections of it.
     */
    FieldSchema fieldSchema(String type);

    /**
     * Top-level field names of {@code type} that are STRUCTURAL — plumbing the
     * pickers should skip (it stays on the data). This is the
     * generic seam for backing-specific exceptions: a bridge translates its
     * domain conventions (e.g. "a statement class's auto-created reify back-ref")
     * into plain field names here, so the workbench applies them without any
     * knowledge of the backing.
     */
    default java.util.Set<String> structuralFields(String type) {
        return DomainSchemas.structuralFields(fieldSchema(type));
    }

    /**
     * Optional authoritative field types for a dynamic sample of {@code type} —
     * the config editors' picker labels, cardinality and structural-hiding, from
     * a compiled model rather than sample reflection. Null (default) reflects the
     * sample, as before.
     */
    default FieldTypeSource fieldTypes(String type) {
        return DomainSchemas.fieldTypes(this, type);
    }

    /** The sample a field-CONFIG table should enumerate from — or {@code null} when
     *  {@code type} has an authoritative field schema ({@link #fieldTypes}). A null sample
     *  makes the config table enumerate straight from the schema, so its structure (a
     *  subclass's inherited + own fields included) cannot drift from {@link #fieldSchema},
     *  and no synthetic shape sample is needed. The {@link #representativeSample} is only a
     *  fallback for schema-less domains. Value pickers and coverage still read instances. */
    default Viewable configSample(String type) {
        FieldTypeSource types = fieldTypes(type);
        return types != null && !types.fieldNames().isEmpty()
                ? null : representativeSample(type);
    }

    /** A representative instance for enumerating {@code type}'s fields. The default is
     *  the first matching instance; a persisted snapshot may return a small shape sample
     *  built from its saved field graph instead of scanning its instance pool. */
    default Viewable representativeSample(String type) {
        for (Viewable q : instances()) {
            if (q != null && type != null && directClasses(q).contains(type)) {
                return q;
            }
        }
        return null;
    }

    /** The instances to run the view over. */
    Collection<? extends Viewable> instances();

    /** Explicit ordinary roots of the domain graph. Referenced instances remain in
     * {@link #instances()} but are not promoted to roots merely because they are reachable. */
    default Collection<? extends Viewable> memberRoots() {
        return instances();
    }

    /** Explicit group-graph roots to render. Their descendants remain ordinary
     * reachable Viewable references and need not be rediscovered as presentation roots. */
    default List<? extends objectview.group.ViewableGroup<?>> groupRoots() {
        return groupRootBindings().stream()
                .map(objectview.viewconfig.DomainGroupRoot::root)
                .toList();
    }

    /** One explicit group root per member type. The association belongs to the domain,
     *  not to the group implementation or its runtime Java class. */
    default List<objectview.viewconfig.DomainGroupRoot> groupRootBindings() {
        return List.of();
    }

    default objectview.group.ViewableGroup<?> groupRoot(String memberType) {
        if (memberType == null) return null;
        List<objectview.group.ViewableGroup<?>> matches = groupRootBindings().stream()
                .filter(binding -> memberType.equals(binding.memberType()))
                .map(objectview.viewconfig.DomainGroupRoot::root)
                .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "Several group roots declared for member type " + memberType);
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    /** The universe class for the {@code ClassTransformPlan} (kept-instances plan). */
    Class<? extends Viewable> universe();
}
