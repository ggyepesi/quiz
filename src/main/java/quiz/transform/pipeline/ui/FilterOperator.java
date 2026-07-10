package quiz.transform.pipeline.ui;

import quiz.transform.ui.FieldKind;

import java.util.EnumSet;
import java.util.Set;

import static quiz.transform.ui.FieldKind.BOOLEAN;
import static quiz.transform.ui.FieldKind.COLLECTION;
import static quiz.transform.ui.FieldKind.ORDERED;
import static quiz.transform.ui.FieldKind.REFERENCE;
import static quiz.transform.ui.FieldKind.TEXT;

public enum FilterOperator {
    EQUALS("equals", BOOLEAN, ORDERED, TEXT, REFERENCE),
    NOT_EQUALS("not equals", BOOLEAN, ORDERED, TEXT, REFERENCE),
    CONTAINS("contains", TEXT, REFERENCE, COLLECTION),
    NOT_CONTAINS("not contains", TEXT, REFERENCE, COLLECTION),
    STARTS_WITH("starts with", TEXT),
    ENDS_WITH("ends with", TEXT),
    LESS_THAN("<", ORDERED),
    LESS_OR_EQUAL("<=", ORDERED),
    GREATER_THAN(">", ORDERED),
    GREATER_OR_EQUAL(">=", ORDERED),
    BETWEEN("between", ORDERED),
    IS_TRUE("is true", BOOLEAN),
    IS_FALSE("is false", BOOLEAN),
    // is empty / is not empty apply to every shape.
    IS_EMPTY("is empty", BOOLEAN, ORDERED, TEXT, REFERENCE, COLLECTION),
    IS_NOT_EMPTY("is not empty", BOOLEAN, ORDERED, TEXT, REFERENCE, COLLECTION);

    private final String label;
    private final Set<FieldKind> kinds;

    FilterOperator(String label, FieldKind... kinds) {
        this.label = label;
        this.kinds = kinds.length == 0 ? EnumSet.noneOf(FieldKind.class)
                : EnumSet.copyOf(java.util.Arrays.asList(kinds));
    }

    /** True if this operator makes sense for {@code kind} (UNKNOWN allows all). */
    public boolean appliesTo(FieldKind kind) {
        return kind == null || kind == FieldKind.UNKNOWN || kinds.contains(kind);
    }

    /** Does this operator need no value at all (a unary predicate)? */
    public boolean isUnary() {
        return this == IS_TRUE || this == IS_FALSE || this == IS_EMPTY || this == IS_NOT_EMPTY;
    }

    /** Does this operator take a second value (a range)? */
    public boolean isBinary() {
        return this == BETWEEN;
    }

    @Override
    public String toString() {
        return label;
    }
}
