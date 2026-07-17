package wikidata.explore.model;

/**
 * Determines what value a statement-class field receives when its configured
 * qualifier is absent from a reified statement.
 */
public enum MissingQualifierPolicy {

    /**
     * Leave the field without a value.
     */
    MISSING,

    /**
     * Use the entity that owns the reified statement.
     */
    STATEMENT_SUBJECT,

    /**
     * Use the main value of the reified statement.
     */
    STATEMENT_VALUE
}
