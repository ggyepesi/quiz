package quiz.transform.pipeline.ui;

import domain.DomainField;

public record FilterCondition(
        DomainField field,
        FilterOperator operator,
        Object value,
        Object value2
) {
    public FilterCondition(DomainField field, Object value) {
        this(field, FilterOperator.EQUALS, value, null);
    }

    @Override
    public String toString() {
        String f = field == null ? "?" : field.displayPath();

        return switch (operator) {
            case IS_TRUE, IS_FALSE, IS_EMPTY, IS_NOT_EMPTY ->
                    f + " " + operator;
            case BETWEEN ->
                    f + " between " + value + " and " + value2;
            default ->
                    f + " " + operator + " " + value;
        };
    }
}