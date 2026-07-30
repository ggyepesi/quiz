package quiz.transform.ui;

import objectview.Viewable;
import quiz.ViewableGroup;
import quiz.transform.View;
import objectview.viewconfig.FieldTypeSource;
import objectview.field.FieldSchema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The transform workbench's logic, with NO Swing: it owns the {@link WorkingDomain}
 * (base + PROJECT/JOIN-derived classes), the operation {@code pipeline}, and the
 * selected member type, and it compiles the pipeline to a {@link View} result. The
 * {@link TransformWorkbenchPanel} is a thin Swing view over this — it renders the
 * controller's state and forwards user actions here.
 *
 * <p>Methods return outcomes or throw with a message rather than showing dialogs,
 * so the view owns all user-facing feedback and this stays headless (and testable).
 */
public final class TransformController {

    private final WorkingDomain domain;
    private final DomainWriter writer;
    private final List<OperationSpec> pipeline = new ArrayList<>();
    private String selectedType;

    // Each member type keeps its OWN steps: switching classes stashes the current
    // pipeline under the outgoing type and restores the incoming type's, so returning
    // to a class brings its groups/filters back (Nomination keeps its seeded default)
    // instead of resetting to empty every time.
    private final Map<String, List<OperationSpec>> pipelineByType = new HashMap<>();

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
        DomainField field = new DomainField(type, name.trim(), false, false,
                kind == null ? objectview.field.FieldKind.UNKNOWN : kind);
        return domain.addField(type, field);
    }
    public Set<String> structuralFields(String type) { return domain.structuralFields(type); }
    public FieldTypeSource fieldTypes(String type) { return domain.fieldTypes(type); }
    public FieldSchema fieldSchema(String type) { return domain.fieldSchema(type); }

    /** Explicit roots for a selected group type, as declared by the source domain. */
    public List<? extends objectview.group.ViewableGroup<?>> groupRoots(String type) {
        if (type == null) {
            return List.of();
        }
        return domain.groupRoots().stream()
                .filter(root -> type.equals(root.typeName()))
                .toList();
    }

    /** How many loaded instances are of {@code type} — shown next to the member-type
     *  selector so the domain's size is visible. */
    public int instanceCount(String type) {
        if (type == null) {
            return 0;
        }
        int n = 0;
        for (Viewable q : domain.instances()) {
            if (q != null && type.equals(q.typeName())) {
                n++;
            }
        }
        return n;
    }

    /** The representative for enumerating {@code type}'s fields — a UNION sample for a
     *  snapshot, so the field pickers (tree / search / sort / group) show EVERY field of
     *  the type, not just those the first instance carries. All callers use it for field
     *  metadata, not to display data. */
    public Viewable sampleOf(String type) {
        Viewable union = domain.representativeSample(type);
        if (union != null) {
            return union;
        }
        for (Viewable q : domain.instances()) {
            if (q != null && type.equals(q.typeName())) {
                return q;
            }
        }
        return null;
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
            if (q == null || !type.equals(q.typeName())) {
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
                if (q == null || !t.equals(q.typeName())) {
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

    // --- selection + pipeline state ------------------------------------------

    public String selectedType() { return selectedType; }

    /** Select a member type. Stashes the current type's steps and restores the
     *  chosen type's remembered steps (empty if it has none yet), so switching
     *  classes and back is non-destructive. */
    public void selectType(String type) {
        if (selectedType != null) {
            pipelineByType.put(selectedType, new ArrayList<>(pipeline));
        }
        this.selectedType = type;
        pipeline.clear();
        List<OperationSpec> remembered = pipelineByType.get(type);
        if (remembered != null) {
            pipeline.addAll(remembered);
        }
    }

    /** The pipeline steps, read-only. */
    public List<OperationSpec> pipeline() {
        return List.copyOf(pipeline);
    }

    public void removeOperation(int index) {
        if (index >= 0 && index < pipeline.size()) {
            pipeline.remove(index);
        }
    }

    /** Move a step by {@code delta}; returns its new index, or -1 if not moved. */
    public int moveOperation(int index, int delta) {
        int n = index + delta;
        if (index >= 0 && n >= 0 && n < pipeline.size()) {
            OperationSpec tmp = pipeline.get(index);
            pipeline.set(index, pipeline.get(n));
            pipeline.set(n, tmp);
            return n;
        }
        return -1;
    }

    // --- adding operations ----------------------------------------------------

    /**
     * The result of an add: {@code error} messages go to a dialog; a non-null
     * {@code createdType} means a new class was materialized (add it to the type
     * pickers) — {@code addToRightClass} also offers it as a JOIN right side;
     * plain steps ({@code ok} with no message/type) just re-render the pipeline.
     */
    public record OpOutcome(boolean ok, String message,
                            String createdType, boolean addToRightClass) {
        static OpOutcome error(String m) { return new OpOutcome(false, m, null, false); }
        static OpOutcome step() { return new OpOutcome(true, null, null, false); }
        static OpOutcome created(String type, String m, boolean right) {
            return new OpOutcome(true, m, type, right);
        }
    }

    public void replaceViewPipeline(List<OperationSpec> ops) {
        pipeline.clear();

        if (ops != null) {
            pipeline.addAll(ops);
        }
    }

    public OpOutcome addOperation(OperationKind kind, List<DomainField> checked,
                                  String valueText, String rightType,
                                  String rightKey, String newType) {
        String memberType = selectedType;
        if (kind == null || memberType == null) {
            return OpOutcome.error("Pick a member type and an operation.");
        }

        // PROJECT is a DOMAIN mutation: materialize a new class from the checked
        // fields and feed it back into the pool — not a step in the view pipeline.
        if (kind == OperationKind.PROJECT_TO_CLASS) {
            if (checked.isEmpty() || newType == null || newType.isBlank()) {
                return OpOutcome.error(
                        "Check the fields to project and enter a new class name.");
            }
            DerivedClass derived = Projector.project(domain, memberType, checked, newType);
            domain.add(derived);
            return OpOutcome.created(newType, "Created class \"" + newType + "\"  ("
                    + derived.instances().size() + " instances, "
                    + derived.fields().size() + " fields) — select it as Members.", false);
        }

        // JOIN is a cross-class DOMAIN mutation: the LEFT key is the checked field,
        // the RIGHT class + key come from the join controls.
        if (kind == OperationKind.JOIN) {
            if (checked.isEmpty() || rightType == null || rightKey == null
                    || newType == null || newType.isBlank()) {
                return OpOutcome.error(
                        "Check the LEFT key, pick the right class + key, and name the new class.");
            }
            DerivedClass derived = Joiner.join(domain, newType,
                    memberType, checked.get(0).field(), rightType, rightKey);
            domain.add(derived);
            long matched = derived.instances().stream()
                    .filter(o -> o instanceof quiz.transform.DynamicViewable d
                            && d.get(rightType.toLowerCase()) != null).count();
            return OpOutcome.created(newType, "Joined \"" + newType + "\"  ("
                    + derived.instances().size() + " rows, " + matched + " matched) — "
                    + "select it as Members (fields: " + memberType.toLowerCase()
                    + ", " + rightType.toLowerCase() + ").", true);
        }

        if (checked.isEmpty()) {
            return OpOutcome.error("Check one field for the operation.");
        }
        DomainField field = checked.get(0);
        OperationSignature sig = OperationSignature.of(kind);
        if (!sig.fieldNeed().accepts(field)) {
            return OpOutcome.error("\"" + field.field() + "\" isn't a "
                    + sig.fieldNeed() + " field for " + kind + ".");
        }
        Object value = sig.needsValue() ? parseValue(valueText) : null;

        // A FILTER is ONE predicate: add this as an AND condition to the existing
        // filter if there is one, else start it — so filters never repeat as steps.
        if (kind == OperationKind.FILTER) {
            for (OperationSpec op : pipeline) {
                if (op.kind == OperationKind.FILTER) {
                    op.addCondition(field, value);
                    return OpOutcome.step();
                }
            }
        }

        pipeline.add(new OperationSpec(kind, field, value));
        return OpOutcome.step();
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

    // --- rendering (compile only — the view assembles the cards) --------------

    /** The view name for a type + pipeline. */
    public static String viewName(String type, List<OperationSpec> ops) {
        return (type == null ? "View" : type)
                + (ops.isEmpty() ? "" : " · " + ops.size() + " op");
    }

    /** Compile a type + pipeline SNAPSHOT and run it over the domain, OFF the EDT —
     *  returns the grouped result the view turns into cards. The caller passes the
     *  snapshot (captured on the EDT) so a background render reads immutable inputs
     *  and its result matches the state at launch. */
    public objectview.group.ViewableGroup<?> compileResult(
            String type, List<OperationSpec> ops) {
        List<OperationSpec> opList = new ArrayList<>(ops);
        String name = viewName(type, opList);

        // A single top-level VALUE group-by becomes a FacetGroup that carries its rule
        // (self-describing + recomputable): filters compose in front via View.members(),
        // then the facet partitions. Nested / multiple / reference groupings keep the full
        // View render.
        List<OperationSpec> groupBys = new ArrayList<>();
        for (OperationSpec o : opList) {
            if (o != null && o.kind == OperationKind.GROUP_BY && o.field != null) {
                groupBys.add(o);
            }
        }
        if (groupBys.size() == 1 && groupBys.get(0).depth == 0
                && !groupBys.get(0).field.reference()) {
            List<OperationSpec> filters = new ArrayList<>();
            for (OperationSpec o : opList) {
                if (o == null || o.kind != OperationKind.GROUP_BY) {
                    filters.add(o);
                }
            }
            View filterView = ViewCompiler.compile(
                    name, type, filters, domain.universe());
            quiz.transform.FacetGroup group = new quiz.transform.FacetGroup(
                    name, type, groupBys.get(0).field.field());
            group.reproduce(filterView.members(domain.instances()));
            return group;
        }

        View view = ViewCompiler.compile(name, type, opList, domain.universe());
        return view.render(domain.instances());
    }

    /** The instances currently SHOWN — the compiled view (filters + grouping) for the
     *  selected type, flattened to distinct leaf members. What Validate should check,
     *  rather than the whole pool. */
    public List<Viewable> viewMembers() {
        if (selectedType == null) {
            return List.of();
        }
        java.util.LinkedHashSet<Viewable> out = new java.util.LinkedHashSet<>();
        collectMembers(compileResult(selectedType, List.copyOf(pipeline)), out);
        return new ArrayList<>(out);
    }

    private static void collectMembers(
            objectview.group.ViewableGroup<?> group, java.util.Set<Viewable> out) {
        if (group == null) {
            return;
        }
        for (Viewable member : group.getMembers()) {
            out.add(member);
        }
        for (objectview.group.ViewableGroup<?> child : group.getChildren()) {
            collectMembers(child, out);
        }
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
