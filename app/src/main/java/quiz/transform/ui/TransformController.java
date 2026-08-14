package quiz.transform.ui;

import objectview.Viewable;
import objectview.viewconfig.FieldTypeSource;
import objectview.field.FieldSchema;
import objectview.field.FieldPath;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The transform workbench's logic, with NO Swing: it owns the {@link WorkingDomain}
 * (base + PROJECT/JOIN-derived classes), the per-type group tree, and the selected
 * member type. The {@link TransformWorkbenchPanel} is a thin Swing view over this —
 * it renders the controller's state and forwards user actions here.
 *
 * <p>Methods return outcomes or throw with a message rather than showing dialogs,
 * so the view owns all user-facing feedback and this stays headless (and testable).
 */
public final class TransformController {

    public static final String ALL_ENTITIES = "All entities";

    private final WorkingDomain domain;
    private quiz.transform.TypeSpec activeTypeSpec;
    private DomainModel activeView;   // cached TypeSpecDomainView for activeTypeSpec
    private final DomainWriter writer;
    private String selectedType;

    // Per member type, a lazily-built map from field path -> a representative
    // non-null value — so field-shape inference is ONE pass over the instances per
    // type (built on first use), not a fresh scan on every field selection.
    private final Map<String, Map<FieldPath, Object>> fieldValueCache = new HashMap<>();

    public TransformController(DomainModel base, DomainWriter writer) {
        this.domain = new WorkingDomain(base);
        this.writer = writer;
    }

    // --- domain queries -------------------------------------------------------

    /** The working domain (base + derived) — for capability checks like {@link
     *  SchemaView}. No Swing here; the view does the instanceof + rendering. */
    public DomainModel domain() { return domain; }

    public List<String> types() {
        if (!domain.exposesEntityUniverse()) return domain.types();
        ArrayList<String> result = new ArrayList<>();
        result.addAll(domain.types());
        result.add(ALL_ENTITIES);
        return result;
    }
    public List<DomainField> fields(String type) {
        return ALL_ENTITIES.equals(type) ? List.of() : schemaDomain(type).fields(type);
    }

    /** Declare a new empty scalar/media field on {@code type} — a schema act that surfaces
     *  it in the field pool + at 0% coverage, to be filled later (e.g. via Find Data).
     *  Returns false if the domain can't carry a declared field (non-dynamic sample). */
    public boolean addField(String type, String name, objectview.field.FieldKind kind) {
        if (type == null || name == null || name.isBlank()) {
            return false;
        }
        objectview.field.FieldKind actualKind = kind == null
                ? objectview.field.FieldKind.UNKNOWN : kind;
        objectview.field.FieldRef field = objectview.field.FieldRef.described(
                name.trim(), actualKind, actualKind, actualKind.name(),
                false, false, null, false, false,
                false, false, "", false);
        return domain.addField(type, field);
    }

    public boolean addField(String type, objectview.field.FieldRef field) {
        return domain.addField(type, field);
    }
    public Set<String> structuralFields(String type) {
        return ALL_ENTITIES.equals(type) ? Set.of() : domain.structuralFields(type);
    }
    public FieldTypeSource fieldTypes(String type) {
        return ALL_ENTITIES.equals(type) ? name -> null : schemaDomain(type).fieldTypes(type);
    }
    public FieldSchema fieldSchema(String type) {
        return ALL_ENTITIES.equals(type) ? null : schemaDomain(type).fieldSchema(type);
    }

    /** Schema for a rendered object in a group-scoped view. The root instance uses the
     *  TypeSpec projection; a referenced object uses its own actual modeled class. */
    public FieldSchema renderedFieldSchema(Viewable instance, String rootType) {
        if (instance == null) return null;
        if (rootType != null && domain.isInstanceOf(instance, rootType)) {
            return fieldSchema(mostSpecificClass(instance, rootType));
        }
        String actual = domain.mostSpecificClass(instance);
        return domain.fieldSchema(actual == null ? instance.typeName() : actual);
    }

    private DomainModel schemaDomain(String type) {
        return activeView != null && activeTypeSpec.instanceClass().equals(type)
                ? activeView : domain;
    }

    /** Select a group and report whether its effective schema differs from the active one. */
    public boolean selectGroup(objectview.group.ViewableGroup<?> group) {
        quiz.transform.TypeSpec effective = effectiveTypeSpec(group);
        if (java.util.Objects.equals(activeTypeSpec, effective)) return false;
        if (effective != null) {
            activeTypeSpec = effective;
            // Cache one view per selected group: rebuildFieldTree and the field-config
            // paths call fields()/fieldTypes()/fieldSchema() repeatedly for the same
            // selection, and each would otherwise rebuild the projection from scratch.
            activeView = new TypeSpecDomainView(domain, activeTypeSpec);
        } else {
            activeTypeSpec = null;
            activeView = null;
        }
        return true;
    }

    /** Type constraints are inherited through ordinary and rule-produced child groups. */
    private static quiz.transform.TypeSpec effectiveTypeSpec(
            objectview.group.ViewableGroup<?> group) {
        java.util.ArrayDeque<quiz.transform.TypeSpec> specs = new java.util.ArrayDeque<>();
        java.util.Set<objectview.group.ViewableGroup<?>> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (objectview.group.ViewableGroup<?> current = group;
             current != null && seen.add(current); current = current.getParent()) {
            if (current instanceof quiz.transform.TypeSpecGroup typed) {
                specs.addFirst(typed.spec());
            }
        }
        quiz.transform.TypeSpec effective = null;
        for (quiz.transform.TypeSpec spec : specs) {
            effective = effective == null ? spec : effective.refinedBy(spec);
        }
        return effective;
    }

    public quiz.transform.TypeSpec effectiveTypeSpec(
            quiz.transform.EditableGroup group) {
        return effectiveTypeSpec((objectview.group.ViewableGroup<?>) group);
    }
    public String baseType(String type) { return domain.baseType(type); }
    public List<String> subtypesOf(String type) { return domain.subtypesOf(type); }
    public boolean isInstanceOf(Viewable instance, String type) {
        return domain.isInstanceOf(instance, type);
    }

    public String mostSpecificClass(Viewable instance, String withinType) {
        String concrete = domain.mostSpecificClass(instance);
        return concrete != null && domain.isSubclassOf(concrete, withinType)
                ? concrete : withinType;
    }

    public Set<String> additionalFields(String subtype) {
        return domain.additionalFields(subtype);
    }

    /** The explicit group root paired with the selected member type. */
    public objectview.group.ViewableGroup<?> groupRoot(String type) {
        if (ALL_ENTITIES.equals(type)) {
            quiz.transform.EditableGroup root = new quiz.transform.EditableGroup(ALL_ENTITIES);
            root.replaceMembers(domain.instances().stream().map(Viewable.class::cast).toList());
            return root;
        }
        return domain.editableGroupRoot(type);
    }

    public quiz.transform.FacetGroup addFacetGroup(
            String type, quiz.transform.EditableGroup parent,
            String name, DomainField field) {
        if (parent == null || field == null) return null;
        quiz.transform.FacetGroup group = new quiz.transform.FacetGroup(
                name, type, field.field());
        group.reproduce(parent.getMembers());
        parent.addGroup(group);
        return group;
    }

    public quiz.transform.OperationGroup addFilterGroup(
            String type, quiz.transform.EditableGroup parent,
            String name, quiz.transform.pipeline.ui.FilterCondition condition) {
        if (parent == null || condition == null) return null;
        quiz.transform.OperationGroup group = new quiz.transform.OperationGroup(
                name, type, condition);
        group.reproduce(parent.getMembers());
        parent.addGroup(group);
        return group;
    }

    public quiz.transform.TypeSpecGroup addTypeSpecGroup(
            quiz.transform.EditableGroup parent, String name,
            quiz.transform.TypeSpec spec) {
        if (parent == null || spec == null || !spec.isConfigured()) return null;
        validateSpecClasses(spec);
        quiz.transform.TypeSpec inherited = effectiveTypeSpec(parent);
        quiz.transform.TypeSpec effective = inherited == null ? spec : inherited.refinedBy(spec);
        validateSpecPaths(effective);
        quiz.transform.TypeSpecGroup group = new quiz.transform.TypeSpecGroup(
                name, spec, domain);
        group.reproduce(parent.getMembers());
        if (group.getMembers().isEmpty()) {
            throw new IllegalArgumentException("The type specification admits no instances.");
        }
        parent.addGroup(group);
        return group;
    }

    /** Reject a spec whose paths cannot be typed against the schema, using the SAME walk
     *  {@code TypeSpecDomainView} projects with ({@link TypeSpecPaths}). Admission
     *  navigates values at runtime while the projection resolves classes statically, so
     *  sharing the walk is what keeps them in agreement — and turns a typo into a clear
     *  message rather than a mystifying empty group. */
    private void validateSpecPaths(quiz.transform.TypeSpec spec) {
        new TypeSpecPaths(domain, spec).validate();
    }

    /** Every class an instance can be admitted BY. The modeled types plus the classes
     *  instances are actually stamped with: a stamped, fieldless class (a Person the
     *  domain has not given fields yet) is a valid restriction even though the field
     *  graph has no shape to enumerate for it. The class picker MUST offer this set and
     *  not the narrower {@link #types()}, or the UI refuses rules the rule engine accepts
     *  — the fieldless-Person case is exactly the one type-spec groups exist for. */
    public List<String> admissionClasses() {
        java.util.LinkedHashSet<String> known =
                new java.util.LinkedHashSet<>(domain.types());
        domain.instances().stream().filter(java.util.Objects::nonNull)
                .flatMap(value -> domain.directClasses(value).stream()).forEach(known::add);
        return List.copyOf(known);
    }

    private void validateSpecClasses(quiz.transform.TypeSpec spec) {
        java.util.Set<String> known = java.util.Set.copyOf(admissionClasses());
        java.util.LinkedHashSet<String> unknown = new java.util.LinkedHashSet<>();
        if (!known.contains(spec.instanceClass())) unknown.add(spec.instanceClass());
        spec.fieldClasses().values().stream().flatMap(java.util.Set::stream)
                .filter(type -> !known.contains(type)).forEach(unknown::add);
        if (!unknown.isEmpty()) throw new IllegalArgumentException(
                "Unknown modeled class(es): " + String.join(", ", unknown));
    }

    public quiz.transform.EditableGroup addManualGroup(
            quiz.transform.EditableGroup parent, String name) {
        if (parent == null) return null;
        quiz.transform.EditableGroup group = new quiz.transform.EditableGroup(name);
        parent.addGroup(group);
        return group;
    }

    /** Define a subclass from a group's members; returns how many were assigned (members
     *  not of {@code baseType} are skipped). Rejects an empty group. */
    public int createSubclassFromGroup(
            String name, String baseType,
            objectview.group.ViewableGroup<?> source) {
        if (source == null) throw new IllegalArgumentException("Select a source group");
        if (source.getMembers().isEmpty()) {
            throw new IllegalArgumentException("The selected group “"
                    + source.getDisplayName() + "” has no members.");
        }
        return domain.defineSubclass(name, baseType, source.getMembers());
    }

    public boolean removeGroup(
            String type, quiz.transform.EditableGroup group) {
        quiz.transform.EditableGroup root = domain.editableGroupRoot(type);
        if (group == null || group == root
                || !(group.getParent() instanceof quiz.transform.EditableGroup parent)) {
            return false;
        }
        parent.removeGroup(group);
        return true;
    }

    /** How many loaded instances are of {@code type} — shown next to the member-type
     *  selector so the domain's size is visible. */
    public int instanceCount(String type) {
        if (type == null) {
            return 0;
        }
        return ALL_ENTITIES.equals(type) ? domain.instances().size()
                : domain.instancesOf(type).size();
    }

    /** The sample a field-config table enumerates from — null for a schema-backed type so
     *  its structure comes straight from the schema (see {@link DomainModel#configSample}),
     *  which cannot drift and needs no synthetic shape. */
    public Viewable configSample(String type) {
        if (ALL_ENTITIES.equals(type)) return null;
        return domain.configSample(type);
    }

    /** A representative non-null value of {@code path} for {@code type} — used to
     *  infer a field's shape when the first instance's value happens to be null (a
     *  common case, e.g. Star.apparentMagnitude). Reads the per-type value map,
     *  built once (see {@link #fieldValues}). */
    public Object sampleFieldValue(String type, FieldPath path) {
        if (type == null || path == null) {
            return null;
        }
        return fieldValues(type).get(path);
    }

    /** Candidate filter values for {@code path}: the full constant set if the field is
     *  a (live) enum, {@code true/false} for a boolean, else its distinct observed
     *  values when low-cardinality. Empty for a high-cardinality or non-scalar field
     *  (numbers, dates, references) so the filter falls back to free-text entry. Lets
     *  the value input offer a picker for enum/categorical fields — e.g. a domain enum
     *  like NobelPrize.Domain or a low-cardinality vocabulary — instead of a blank box. */
    public List<String> candidateValues(String type, FieldPath path) {
        if (type == null || path == null) {
            return List.of();
        }
        Object sample = sampleFieldValue(type, path);
        if (sample instanceof Enum<?> e) {
            List<String> out = new ArrayList<>();
            for (Object c : e.getClass().getEnumConstants()) {
                String s = c.toString();
                if (!s.isBlank()) {
                    out.add(s);
                }
            }
            return out;
        }
        if (sample instanceof Boolean) {
            return List.of("true", "false");
        }
        if (!(sample instanceof CharSequence)) {
            return List.of();
        }
        final int cap = 25;
        java.util.TreeSet<String> distinct = new java.util.TreeSet<>();
        for (Viewable q : domain.instances()) {
            if (q == null || !domain.isInstanceOf(q, type)) {
                continue;
            }
            Object v = objectview.field.FieldAccess.getPath(q, path);
            if (v == null) {
                continue;
            }
            if (!(v instanceof CharSequence)) {
                return List.of();
            }
            String s = v.toString();
            if (!s.isBlank()) {
                distinct.add(s);
            }
            if (distinct.size() > cap) {
                return List.of();
            }
        }
        return new ArrayList<>(distinct);
    }

    /** The per-type {@code path -> representative non-null value} map, built in ONE
     *  pass over the instances (stopping once every field is resolved) and cached. */
    private Map<FieldPath, Object> fieldValues(String type) {
        return fieldValueCache.computeIfAbsent(type, t -> {
            List<FieldPath> paths = new ArrayList<>();
            for (DomainField f : domain.fields(t)) {
                paths.add(f.fieldPath());
            }
            Map<FieldPath, Object> values = new HashMap<>();
            int scanned = 0;
            for (Viewable q : domain.instances()) {
                if (q == null || !domain.isInstanceOf(q, t)) {
                    continue;
                }
                boolean complete = true;
                for (FieldPath p : paths) {
                    if (values.containsKey(p)) {
                        continue;
                    }
                    Object v = objectview.field.FieldAccess.getPath(q, p);
                    if (v != null) {
                        values.put(p, v);
                    } else {
                        complete = false;
                    }
                }
                if (complete || ++scanned >= 5000) {
                    break;
                }
            }
            return values;
        });
    }

    /** A DomainField for a dotted path — shape from the domain (else scalar). */
    public DomainField field(String type, FieldPath path) {
        for (DomainField df : domain.fields(type)) {
            if (df.fieldPath().equals(path)) {
                return df;
            }
        }
        return new DomainField(type, path, false, false);
    }

    /** Resolve the checked dotted paths to typed DomainFields of {@code type}. */
    public List<DomainField> resolveFields(String type, List<FieldPath> paths) {
        List<DomainField> out = new ArrayList<>();
        for (FieldPath p : paths) {
            out.add(field(type, p));
        }
        return out;
    }

    // --- selection state ------------------------------------------------------

    public String selectedType() { return selectedType; }

    /** Select a member type. Each type's group tree is remembered independently
     *  (see {@link #groupRoot}), so switching classes and back is non-destructive. */
    public void selectType(String type) {
        this.selectedType = type;
    }

    /** Parse a filter literal: true/false, int, double, else the trimmed string. */
    public static Object parseValue(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.trim();
        if (s.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (s.equalsIgnoreCase("false")) return Boolean.FALSE;
        try { return Integer.valueOf(s); } catch (NumberFormatException ignored) { }
        try { return Double.valueOf(s); } catch (NumberFormatException ignored) { }
        return s;
    }

    // --- save -----------------------------------------------------------------

    public boolean canSave() { return writer != null; }

    /** Persist the WHOLE domain — every type's instances, the full reachable closure —
     *  as a first-class domain, regardless of the selected type or pipeline, so picking
     *  a nested type can't silently drop the roots. (Saving a derived/transformed subset
     *  will be a separate action.) Returns the writer's status; throws on failure. */
    public String saveAsDomain(String name) throws Exception {
        return writer.save(name, domain.instances(), domain);
    }
}
