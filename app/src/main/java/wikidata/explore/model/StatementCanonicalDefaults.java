package wikidata.explore.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Proposes the initial canonical identity for a statement class — both the
 * natural key and the display-name field.
 *
 * <p>The distinction between a <em>proposal</em> and the identity rule itself is
 * intentional. {@link CanonicalSpec} is persisted model data and is the sole
 * authority used by validation, compilation and reification. This helper is
 * called only by editors/model builders while configuring a class; it is never
 * consulted while reading, compiling or executing a completed model.
 * Consequently an explicitly empty key remains empty at runtime instead of
 * silently acquiring a different meaning.</p>
 *
 * <p>The proposed statement grain is the statement value plus scalar entity
 * qualifiers, scalar date qualifiers, and an explicitly declared statement
 * subject. Collection qualifiers denote
 * zero-or-more participants rather than one stable key component; and derived
 * fields such as {@code COMPANION_MATCH} do not exist when reification performs
 * its identity-based deduplication.</p>
 *
 * <p>The proposed display name is the first single-valued, non-derived field —
 * a reified statement has no Wikidata label of its own, so the class-default
 * {@code LABEL} mode would leave it nameless.</p>
 */
public final class StatementCanonicalDefaults {
    private StatementCanonicalDefaults() { }

    public static List<String> suggest(GeneratedClassModel owner) {
        if (owner == null || !owner.reifiesStatements()) {
            return List.of();
        }

        LinkedHashSet<String> result = new LinkedHashSet<>();
        // The ps:<statement property> value is the first and primary component.
        // statementValueFieldName uses the explicit property mapping; it does not
        // guess that an arbitrary first field is the value.
        String valueField =
                StatementFieldSemantics.statementValueFieldName(owner);
        if (!valueField.isBlank()) {
            GeneratedFieldModel value = owner.fields().stream()
                    .filter(field -> valueField.equals(field.name()))
                    .findFirst()
                    .orElse(null);
            // A statement value is normally scalar, but the editor permits other
            // shapes while a model is being assembled. Do not materialize a key
            // which validation will immediately reject; DATE follows the same
            // default policy as date qualifiers and remains an attribute unless
            // the modeller explicitly selects it.
            if (StatementFieldSemantics.isCanonicalKeyCandidate(value)
                    && value.type() != FieldType.DATE) {
                result.add(valueField);
            }
        }

        // Scalar ENTITY and DATE qualifiers extend the default grain. A time is
        // part of distinguishing repeated statements, regardless of whether the
        // field deliberately projects it to YEAR or retains its full precision.
        // The shared
        // StatementFieldSemantics predicate excludes post-reification producers.
        for (GeneratedFieldModel field : owner.fields()) {
            if (StatementFieldSemantics.isQualifierField(owner, field)
                    && (field.type() == FieldType.ENTITY
                            || field.type() == FieldType.DATE)
                    && field.cardinality() != FieldCardinality.COLLECTION) {
                result.add(field.name());
            }
            if (StatementFieldSemantics.isStatementSubjectField(owner, field)
                    && field.type() == FieldType.ENTITY
                    && field.cardinality() != FieldCardinality.COLLECTION) {
                result.add(field.name());
            }
            // Unlike an arbitrary collection, a participant field is a normalized
            // mathematical set available before deduplication. Its sorted entity ids
            // are therefore a stable part of a shared statement's natural grain.
            if (field.mapping().productionKind()
                    == FieldProductionKind.STATEMENT_PARTICIPANTS) {
                result.add(field.name());
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * The proposed display-name field: the first single-valued, non-derived
     * field (the statement value or a scalar qualifier). Collection, name and
     * derived fields such as {@code COMPANION_MATCH} are unsuitable — the shared
     * {@link StatementFieldSemantics#isCanonicalKeyCandidate} predicate excludes
     * exactly those. Returns {@code ""} when no field qualifies, leaving the
     * {@code LABEL} default in place.
     */
    public static String suggestDisplayField(GeneratedClassModel owner) {
        if (owner == null || !owner.reifiesStatements()) {
            return "";
        }
        for (GeneratedFieldModel field : owner.fields()) {
            if (StatementFieldSemantics.isCanonicalKeyCandidate(field)) {
                return field.name();
            }
        }
        return "";
    }

    public static boolean usesSuggestedDisplay(GeneratedClassModel owner) {
        if (owner == null) {
            return false;
        }
        String suggested = suggestDisplayField(owner);
        CanonicalSpec canonical = owner.canonical();
        return suggested.isBlank()
                ? canonical.displayNameMode() == CanonicalSpec.DisplayNameMode.LABEL
                : canonical.displayNameMode() == CanonicalSpec.DisplayNameMode.FIELD
                        && suggested.equals(canonical.displayNameField());
    }

    public static void replaceKeyWithSuggestion(GeneratedClassModel owner) {
        if (owner == null) {
            return;
        }
        owner.canonical().keyFields().clear();
        owner.canonical().keyFields().addAll(suggest(owner));
    }

    public static void replaceDisplayWithSuggestion(GeneratedClassModel owner) {
        if (owner == null) {
            return;
        }
        CanonicalSpec canonical = owner.canonical();
        String displayField = suggestDisplayField(owner);
        if (displayField.isBlank()) {
            canonical.displayNameMode(CanonicalSpec.DisplayNameMode.LABEL);
            canonical.displayNameField("");
        } else {
            canonical.displayNameMode(CanonicalSpec.DisplayNameMode.FIELD);
            canonical.displayNameField(displayField);
        }
    }

    public static void replaceWithSuggestion(GeneratedClassModel owner) {
        if (owner == null) {
            return;
        }
        // This method deliberately MUTATES the editable model. Callers must do so
        // only as an explicit class-creation operation, never as a getter fallback.
        // Incremental field editing uses the two narrower methods above so changing
        // a default key cannot overwrite an independently customized display rule.
        replaceKeyWithSuggestion(owner);
        replaceDisplayWithSuggestion(owner);
    }
}
