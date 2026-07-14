package wikidata.explore.model;

/**
 * Shared StatementClass field rules used by the editor, validator and runtime.
 *
 * <p>Keeping these predicates outside Swing prevents the UI from gradually
 * developing a different idea of a valid statement field than generation and
 * validation.</p>
 */
public final class StatementFieldSemantics {

    private StatementFieldSemantics() {
    }

    /**
     * A field exists on the provisional statement record loaded from Wikidata,
     * rather than being produced by a later transform.
     */
    public static boolean isRuntimeStatementField(
            GeneratedFieldModel field) {

        return field != null
                && !field.isNameField()
                && field.mapping().productionKind()
                == FieldProductionKind.AUTO;
    }

    /**
     * True when the field directly reads a qualifier from a statement class.
     */
    public static boolean isQualifierField(
            GeneratedClassModel owner,
            GeneratedFieldModel field) {

        return owner != null
                && owner.reifiesStatements()
                && isRuntimeStatementField(field)
                && field.mapping().isQualifier();
    }

    /**
     * Missing-qualifier fallback currently has meaningful runtime semantics for
     * scalar entity qualifiers. Collection qualifiers already represent zero or
     * more values, while scalar/date/text fallbacks would require conversion
     * rules that are intentionally not inferred here.
     */
    public static boolean supportsMissingQualifierPolicy(
            GeneratedClassModel owner,
            GeneratedFieldModel field) {

        return isQualifierField(owner, field)
                && field.type() == FieldType.ENTITY
                && field.cardinality()
                != FieldCardinality.COLLECTION;
    }

    /**
     * Removes a fallback policy which is no longer valid after the field's
     * qualifier, type, cardinality or production kind was changed.
     *
     * @return true when the mapping was changed
     */
    public static boolean normalizeMissingQualifierPolicy(
            GeneratedClassModel owner,
            GeneratedFieldModel field) {

        if (field == null
                || field.mapping()
                        .missingQualifierPolicy() == null
                || supportsMissingQualifierPolicy(owner, field)) {
            return false;
        }

        field.mapping().missingQualifierPolicy(null);
        return true;
    }

    /**
     * Fields eligible for a derived class's canonical key. In particular,
     * COMPANION_MATCH fields such as Oscar Nomination.won are excluded.
     */
    public static boolean isCanonicalKeyCandidate(
            GeneratedFieldModel field) {

        return isRuntimeStatementField(field)
                && field.cardinality()
                != FieldCardinality.COLLECTION;
    }
}
