package quiz.transform.ui;

import objectview.Viewable;
import objectview.viewconfig.FieldTypeSource;
import objectview.field.FieldSchema;

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

    private final WorkingDomain domain;
    private final DomainWriter writer;
    private String selectedType;

    // Per member type, a lazily-built map from field path -> a representative
    // non-null value — so field-shape inference is ONE pass over the instances per
    // type (built on first use), not a fresh scan on every field selection.
    private final Map<String, Map<String, Object>> fieldValueCache = new HashMap<>();

    public TransformController(DomainModel base, DomainWriter writer) {
        this.domain = new WorkingDomain(base);
        this.writer = writer;
    }

    // --- domain queries -------------------------------------------------------

    /** The working domain (base + derived) — for capability checks like {@link
     *  SchemaView}. No Swing here; the view does the instanceof + rendering. */
    public DomainModel domain() { return domain; }

    public List<String> types() { return domain.types(); }
    public List<DomainField> fields(String type) { return domain.fields(type); }

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
                false, false, "", false, false);
        return domain.addField(type, field);
    }

    public boolean addField(String type, objectview.field.FieldRef field) {
        return domain.addField(type, field);
    }
    public Set<String> structuralFields(String type) { return domain.structuralFields(type); }
    public FieldTypeSource fieldTypes(String type) { return domain.fieldTypes(type); }
    public FieldSchema fieldSchema(String type) { return domain.fieldSchema(type); }
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
        return domain.instancesOf(type).size();
    }

    /** The sample a field-config table enumerates from — null for a schema-backed type so
     *  its structure comes straight from the schema (see {@link DomainModel#configSample}),
     *  which cannot drift and needs no synthetic shape. */
    public Viewable configSample(String type) {
        return domain.configSample(type);
    }

    /** A representative non-null value of {@code path} for {@code type} — used to
     *  infer a field's shape when the first instance's value happens to be null (a
     *  common case, e.g. Star.apparentMagnitude). Reads the per-type value map,
     *  built once (see {@link #fieldValues}). */
    public Object sampleFieldValue(String type, String path) {
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
    public List<String> candidateValues(String type, String path) {
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
    private Map<String, Object> fieldValues(String type) {
        return fieldValueCache.computeIfAbsent(type, t -> {
            List<String> paths = new ArrayList<>();
            for (DomainField f : domain.fields(t)) {
                paths.add(f.field());
            }
            Map<String, Object> values = new HashMap<>();
            int scanned = 0;
            for (Viewable q : domain.instances()) {
                if (q == null || !domain.isInstanceOf(q, t)) {
                    continue;
                }
                boolean complete = true;
                for (String p : paths) {
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
    public DomainField field(String type, String path) {
        for (DomainField df : domain.fields(type)) {
            if (df.field().equals(path)) {
                return df;
            }
        }
        return new DomainField(type, path, false, false);
    }

    /** Resolve the checked dotted paths to typed DomainFields of {@code type}. */
    public List<DomainField> resolveFields(String type, List<String> paths) {
        List<DomainField> out = new ArrayList<>();
        for (String p : paths) {
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
