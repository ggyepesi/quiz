package quiz.transform.ui;

import quiz.Quizable;
import quiz.QuizableGroup;
import quiz.transform.View;
import quiz.ui.viewconfig.FieldTypeSource;

import java.util.ArrayList;
import java.util.List;
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
    public Set<String> structuralFields(String type) { return domain.structuralFields(type); }
    public FieldTypeSource fieldTypes(String type) { return domain.fieldTypes(type); }

    public Quizable sampleOf(String type) {
        for (Quizable q : domain.instances()) {
            if (q != null && type.equals(q.typeName())) {
                return q;
            }
        }
        return null;
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

    /** Select a member type — this resets the pipeline (a fresh view of it). */
    public void selectType(String type) {
        this.selectedType = type;
        pipeline.clear();
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
                    .filter(o -> o instanceof quiz.transform.DynamicQuizable d
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
    public QuizableGroup compileResult(String type, List<OperationSpec> ops) {
        View view = ViewCompiler.compile(
                viewName(type, ops), type, new ArrayList<>(ops), domain.universe());
        return view.render(domain.instances());
    }

    // --- save -----------------------------------------------------------------

    public boolean canSave() { return writer != null; }

    /** Persist the current view's members as a first-class domain; returns the
     *  writer's status message. Throws on failure. */
    public String saveAsDomain(String name) throws Exception {
        View view = ViewCompiler.compile(name, selectedType, pipeline, domain.universe());
        List<? extends Quizable> members = view.members(domain.instances());
        return writer.save(name, members);
    }

    // --- default seed ---------------------------------------------------------

    /** Seed a ready-to-run "Oscar winners by category by year" if the snapshot
     *  supports it; returns true when it seeded (so the view can sync). */
    public boolean seedDefault() {
        if (!domain.types().contains("Nomination")) {
            return false;
        }
        selectedType = "Nomination";
        pipeline.clear();
        DomainField won = field("Nomination", "won");
        DomainField category = field("Nomination", "category");
        DomainField year = field("Nomination", "year");
        pipeline.add(new OperationSpec(OperationKind.FILTER, won, Boolean.TRUE));
        pipeline.add(new OperationSpec(OperationKind.GROUP_BY_REFERENCE, category, null));
        pipeline.add(new OperationSpec(OperationKind.GROUP_BY_VALUE, year, null));
        return true;
    }
}
