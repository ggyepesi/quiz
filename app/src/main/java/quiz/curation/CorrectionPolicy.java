package quiz.curation;

/** How a persisted field-value directive is replayed over regenerated base data. */
public enum CorrectionPolicy {
    /** Replace the base value with the reviewed value. */
    REPLACE,
    /** Apply only while the target field has no usable value. */
    FILL_IF_EMPTY,
    /** Add the reviewed value(s) to the target collection without duplicates. */
    ADD_TO_COLLECTION,
    /** Alias-style collection addition; currently replayed like ADD_TO_COLLECTION. */
    ADD_AS_ALIAS
}
