package quiz.transform.pipeline.ui;

/**
 * The value shape of a field, used to offer only the {@link FilterOperator}s that
 * make sense for it (e.g. {@code <} / {@code between} for numbers and dates, {@code
 * contains} for text and collections). {@link #UNKNOWN} allows every operator —
 * the safe fallback when the shape can't be determined.
 */
public enum FieldKind {
    BOOLEAN,      // true/false — is true / is false / equals
    ORDERED,      // number or date — comparisons + between
    TEXT,         // string — contains / starts with / ends with
    REFERENCE,    // an entity — equals / contains (by label)
    COLLECTION,   // multi-valued — contains / is empty
    UNKNOWN
}
