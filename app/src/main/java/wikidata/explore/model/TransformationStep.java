package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import quiz.transform.FilterOperator;

/** One provider-neutral elementary operation in a saved transformation. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class TransformationStep {
    public enum Kind { FILTER }

    private Kind kind = Kind.FILTER;
    private String fieldPath = "";
    private FilterOperator operator = FilterOperator.EQUALS;
    private Object value;
    private Object value2;

    public TransformationStep() { }

    public static TransformationStep filter(String fieldPath, FilterOperator operator,
                                            Object value, Object value2) {
        TransformationStep step = new TransformationStep();
        step.fieldPath(fieldPath);
        step.operator(operator);
        step.value(value);
        step.value2(value2);
        return step;
    }

    public Kind kind() { return kind == null ? Kind.FILTER : kind; }
    public void kind(Kind value) { kind = value == null ? Kind.FILTER : value; }
    public String fieldPath() { return fieldPath == null ? "" : fieldPath.trim(); }
    public void fieldPath(String value) { fieldPath = value == null ? "" : value.trim(); }
    public FilterOperator operator() {
        return operator == null ? FilterOperator.EQUALS : operator;
    }
    public void operator(FilterOperator value) {
        operator = value == null ? FilterOperator.EQUALS : value;
    }
    public Object value() { return value; }
    public void value(Object value) { this.value = value; }
    public Object value2() { return value2; }
    public void value2(Object value) { value2 = value; }

    public boolean configured() { return kind() == Kind.FILTER && !fieldPath().isBlank(); }

    public TransformationStep copy() {
        return filter(fieldPath(), operator(), value, value2);
    }
}
