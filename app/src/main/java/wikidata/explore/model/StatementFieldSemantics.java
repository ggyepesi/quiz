package wikidata.explore.model;

import datasource.schema.FieldType;

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

    /**
     * The name of the field that plays the SUBJECT role — the entity the statement is
     * made about ({@code the item carrying the statement}). Resolved the same way the
     * value role is, and for the same reason: the subject is a real role on a reified
     * statement, and every consumer must read it from one place or each will invent a
     * different idea of which field it is.
     *
     * <p>The subject is an authored role, not a shape inferred from an otherwise
     * unmapped entity field. Returns {@code ""} when no direct subject field is
     * configured. A statement may instead expose its subject through explicit
     * subject-fallback or participant fields; see
     * {@link #receivesStatementSubject(GeneratedClassModel, GeneratedFieldModel)}.
     */
    public static String statementSubjectFieldName(GeneratedClassModel owner) {
        if (owner == null || !owner.reifiesStatements()) {
            return "";
        }
        return owner.fields().stream()
                .filter(field -> isStatementSubjectField(owner, field))
                .map(GeneratedFieldModel::name)
                .findFirst().orElse("");
    }

    /** True when {@code field} plays the subject role — see
     *  {@link #statementSubjectFieldName}. */
    public static boolean isStatementSubject(
            GeneratedClassModel owner, GeneratedFieldModel field) {
        return field != null && field.name() != null
                && field.name().equals(statementSubjectFieldName(owner));
    }

    /**
     * Whether this declared field can receive the statement's subject entity.
     *
     * <p>This is the complete authored vocabulary: a direct subject field, a scalar
     * qualifier explicitly falling back to the subject, or a participants collection
     * that explicitly combines the subject with qualifier values. No field shape is
     * interpreted as a subject implicitly.
     */
    public static boolean receivesStatementSubject(
            GeneratedClassModel owner, GeneratedFieldModel field) {
        if (isStatementSubjectField(owner, field)) return true;
        if (owner == null || !owner.reifiesStatements() || field == null) return false;
        if (field.mapping().productionKind()
                == FieldProductionKind.STATEMENT_PARTICIPANTS) return true;
        return supportsMissingQualifierPolicy(owner, field)
                && effectiveMissingQualifierPolicy(
                        field.mapping().missingQualifierPolicy())
                        == MissingQualifierPolicy.STATEMENT_SUBJECT;
    }

    /** Whether the statement declares any visible destination for its subject. */
    public static boolean hasStatementSubjectBinding(GeneratedClassModel owner) {
        return owner != null && owner.fields().stream()
                .anyMatch(field -> receivesStatementSubject(owner, field));
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

        // A COLLECTION never identifies, participants included. That exception was
        // added on the theory that a participant set is a stable natural key, and the
        // Nobel data disproved it: 393 award statements state no "together with" at
        // all, so the set is really whichever co-laureates that one statement happened
        // to name, and identifying by it split shares that belong together. The answer
        // is to keep participants OUT of the key and let the duplicate policy union
        // them, which is one mechanism for the question rather than two.
        return (isRuntimeStatementField(field)
                || field != null && field.mapping().productionKind()
                        == FieldProductionKind.STATEMENT_SUBJECT)
                && field.cardinality() != FieldCardinality.COLLECTION;
    }
}
