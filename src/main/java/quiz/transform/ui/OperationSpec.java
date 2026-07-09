package quiz.transform.ui;

import quiz.transform.pipeline.ui.FilterCondition;

/**
 * One configured operation in the transform pipeline: an {@link OperationKind}, the
 * domain field it operates on, and (for a filter) the literal value. A FILTER is a
 * single predicate — the AND of its {@link #conditions} — so there is at most one
 * FILTER in a pipeline. The pipeline compiles to a real {@link quiz.transform.View}
 * — see {@link ViewCompiler}.
 */
public class OperationSpec {

    public OperationKind kind;
    public DomainField field;   // GROUP_BY/PROJECT/JOIN; a FILTER's FIRST condition field
    public Object value;        // a FILTER's FIRST condition value (back-compat)
    // The FILTER predicate's AND conditions (null for non-filters).
    public java.util.List<FilterCondition> conditions;
    // GROUP_BY only: this group's depth in the dimension tree. 0 (default) = a
    // top-level dimension off the root; a deeper value nests the group within the
    // bucket of the group at depth-1, so siblings at one depth are parallel.
    public int depth;

    public OperationSpec() {}

    public OperationSpec(OperationKind kind, DomainField field, Object value) {
        this.kind = kind;
        this.field = field;
        this.value = value;
        if (kind == OperationKind.FILTER && field != null) {
            this.conditions = new java.util.ArrayList<>();
            this.conditions.add(new FilterCondition(field, value));
        }
    }

    /** Append an AND condition to this FILTER predicate. */
    public void addCondition(DomainField f, Object v) {
        if (conditions == null) {
            conditions = new java.util.ArrayList<>();
        }
        conditions.add(new FilterCondition(f, v));
    }

    @Override
    public String toString() {
        if (kind == null) {
            return "(incomplete operation)";
        }
        return switch (kind) {
            case FILTER -> "filter  " + filterText();
            case GROUP_BY -> (field == null ? "group by" : "group by  " + field.path())
                    + (depth > 0 ? "  ·nested@" + depth : "");
            case PROJECT_TO_CLASS -> field == null ? "project" : "project  " + field.path();
            case JOIN -> field == null ? "join" : "join  " + field.path();
        };
    }

    /** The FILTER predicate as text: {@code f1 == v1 AND f2 == v2 …}. */
    public String filterText() {
        java.util.List<FilterCondition> cs = conditions;
        if (cs == null || cs.isEmpty()) {
            return field == null ? "" : field.path() + " == " + value;
        }
        StringBuilder sb = new StringBuilder();
        for (FilterCondition c : cs) {
            if (sb.length() > 0) {
                sb.append(" AND ");
            }
            sb.append(c.field() == null ? "?" : c.field().path()).append(" == ").append(c.value());
        }
        return sb.toString();
    }
}
