package quiz.transform.pipeline.ui;

public enum FilterOperator {
    EQUALS("equals"),
    NOT_EQUALS("not equals"),
    CONTAINS("contains"),
    NOT_CONTAINS("not contains"),
    STARTS_WITH("starts with"),
    ENDS_WITH("ends with"),
    LESS_THAN("<"),
    LESS_OR_EQUAL("<="),
    GREATER_THAN(">"),
    GREATER_OR_EQUAL(">="),
    BETWEEN("between"),
    IS_TRUE("is true"),
    IS_FALSE("is false"),
    IS_EMPTY("is empty"),
    IS_NOT_EMPTY("is not empty");

    private final String label;

    FilterOperator(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}