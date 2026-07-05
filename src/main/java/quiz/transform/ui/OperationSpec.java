package quiz.transform.ui;

/**
 * One configured operation in the transform pipeline: an {@link OperationKind}, the
 * domain field it operates on, and (for a filter) the literal value. The pipeline
 * of these compiles to a real {@link quiz.transform.View} — see {@link ViewCompiler}.
 */
public class OperationSpec {

    public OperationKind kind;
    public DomainField field;
    public Object value;   // only for FILTER

    public OperationSpec() {}

    public OperationSpec(OperationKind kind, DomainField field, Object value) {
        this.kind = kind;
        this.field = field;
        this.value = value;
    }

    @Override
    public String toString() {
        if (kind == null || field == null) {
            return "(incomplete operation)";
        }
        return switch (kind) {
            case FILTER -> "filter  " + field.path() + " == " + value;
            case GROUP_BY_VALUE -> "group by value  " + field.path();
            case GROUP_BY_REFERENCE -> "invert / group by  " + field.path();
            case PROJECT_TO_CLASS -> "project  " + field.path();
            case JOIN -> "join  " + field.path();
        };
    }
}
