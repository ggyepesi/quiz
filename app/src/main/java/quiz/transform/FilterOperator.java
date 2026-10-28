package quiz.transform;

import objectview.field.FieldKind;

import java.util.EnumSet;
import java.util.Set;

import static objectview.field.FieldKind.BOOLEAN;
import static objectview.field.FieldKind.COLLECTION;
import static objectview.field.FieldKind.MEDIA;
import static objectview.field.FieldKind.ORDERED;
import static objectview.field.FieldKind.REFERENCE;
import static objectview.field.FieldKind.TEXT;

/** Provider-neutral predicate vocabulary shared by the editor and headless transforms. */
public enum FilterOperator {
    EQUALS("equals", BOOLEAN, ORDERED, TEXT, REFERENCE),
    NOT_EQUALS("not equals", BOOLEAN, ORDERED, TEXT, REFERENCE),
    CONTAINS("contains", TEXT, REFERENCE, COLLECTION),
    NOT_CONTAINS("not contains", TEXT, REFERENCE, COLLECTION),
    SIZE_EQUALS("size =", COLLECTION),
    SIZE_GREATER_THAN("size >", COLLECTION),
    SIZE_LESS_THAN("size <", COLLECTION),
    STARTS_WITH("starts with", TEXT),
    ENDS_WITH("ends with", TEXT),
    LESS_THAN("<", ORDERED),
    LESS_OR_EQUAL("<=", ORDERED),
    GREATER_THAN(">", ORDERED),
    GREATER_OR_EQUAL(">=", ORDERED),
    BETWEEN("between", ORDERED),
    IS_TRUE("is true", BOOLEAN),
    IS_FALSE("is false", BOOLEAN),
    IS_EMPTY("is empty", BOOLEAN, ORDERED, TEXT, REFERENCE, COLLECTION, MEDIA),
    IS_NOT_EMPTY("is not empty", BOOLEAN, ORDERED, TEXT, REFERENCE, COLLECTION, MEDIA);

    private final String label;
    private final Set<FieldKind> kinds;

    FilterOperator(String label, FieldKind... kinds) {
        this.label = label;
        this.kinds = kinds.length == 0 ? EnumSet.noneOf(FieldKind.class)
                : EnumSet.copyOf(java.util.Arrays.asList(kinds));
    }

    public boolean appliesTo(FieldKind kind) {
        return kind == null || kind == FieldKind.UNKNOWN || kinds.contains(kind);
    }

    public boolean isUnary() {
        return this == IS_TRUE || this == IS_FALSE || this == IS_EMPTY || this == IS_NOT_EMPTY;
    }

    public boolean isBinary() { return this == BETWEEN; }

    @Override public String toString() { return label; }
}
