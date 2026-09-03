package wikidata.explore.model;

import datasource.schema.FieldType;

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
 * <p>It proposes a NAME only. Proposing a KEY is gone: that heuristic swept in the
 * statement value and every scalar entity/date qualifier, was written on class
 * creation, rewritten whenever an unrelated field was edited, and offered behind a
 * "Re-derive identity" button — so a class carried an identity nobody had chosen. What
 * a statement is identified by now starts from its triple; see {@link
 * StatementIdentity}.</p>
 *
 * <p>The proposed display name is the first single-valued, non-derived field —
 * a reified statement has no Wikidata label of its own, so the class-default
 * {@code LABEL} mode would leave it nameless.</p>
 */
public final class StatementDisplayDefaults {
    private StatementDisplayDefaults() { }

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

}
