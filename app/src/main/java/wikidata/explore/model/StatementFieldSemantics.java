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
                && (field.mapping().productionKind() == FieldProductionKind.AUTO
                    || field.mapping().productionKind()
                        == FieldProductionKind.STATEMENT_PARTICIPANTS);
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

    /** True when the field explicitly denotes the entity carrying the statement. */
    public static boolean isStatementSubjectField(
            GeneratedClassModel owner, GeneratedFieldModel field) {
        return owner != null
                && owner.reifiesStatements()
                && field != null
                && field.mapping().productionKind()
                        == FieldProductionKind.STATEMENT_SUBJECT;
    }

    /**
     * The name of the field that plays the VALUE role — the reified statement's main
     * value ({@code ps:<pid>}). It is the runtime, non-qualifier field whose property
     * is the class's statement-source PID (the explicit link the modeller sets).
     * Returns {@code ""} when no such field exists — deliberately WITHOUT the old
     * "first non-qualifier field" guess, so a missing value field is a validation
     * error rather than a silently wrong reification. Orthogonal to
     * {@link #isQualifierField}: the value field is never a qualifier.
     */
    public static String statementValueFieldName(GeneratedClassModel owner) {
        if (owner == null || !owner.reifiesStatements()) {
            return "";
        }
        StatementClassSource source = owner.statementSource();
        if (source == null) {
            return "";
        }
        String statementPid = trim(source.propertyPid());
        if (statementPid.isEmpty()) {
            return "";
        }
        for (GeneratedFieldModel field : owner.fields()) {
            if (!isRuntimeStatementField(field) || field.mapping().isQualifier()) {
                continue;
            }
            if (statementPid.equals(trim(field.mapping().propertyPid()))) {
                return field.name();
            }
        }
        return "";
    }

    /** True when {@code field} plays the value role — see
     *  {@link #statementValueFieldName}. */
    public static boolean isStatementValueField(
            GeneratedClassModel owner, GeneratedFieldModel field) {
        return field != null && field.name() != null
                && field.name().equals(statementValueFieldName(owner));
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
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
     * The one runtime interpretation of an omitted missing-qualifier policy.
     * Absence remains absence: copying the statement subject or value must be an
     * explicit modelling decision.
     */
    public static MissingQualifierPolicy effectiveMissingQualifierPolicy(
            MissingQualifierPolicy configured) {
        return configured == null ? MissingQualifierPolicy.MISSING : configured;
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
     * Fields eligible for a derived class's explicitly configured canonical
     * key. In particular, COMPANION_MATCH fields such as Oscar Nomination.won
     * are excluded because they do not exist at reification time.
     *
     * <p>This is deliberately broader than the automatic proposal in
     * {@link StatementCanonicalDefaults}: a modeller may explicitly decide that
     * a scalar date participates in identity, although dates are omitted from
     * the initial default.</p>
     */
    public static boolean isCanonicalKeyCandidate(
            GeneratedFieldModel field) {

        return (isRuntimeStatementField(field)
                || field != null && field.mapping().productionKind()
                        == FieldProductionKind.STATEMENT_SUBJECT)
                && (field.cardinality() != FieldCardinality.COLLECTION
                    || field.mapping().productionKind()
                        == FieldProductionKind.STATEMENT_PARTICIPANTS);
    }
}
