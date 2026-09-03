package wikidata.explore.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * What identifies one occurrence of a statement.
 *
 * <p>A statement class fixes its property, so the triple's two remaining places — the
 * subject and the object — are what every one of its instances is <i>about</i>. They are
 * therefore part of its identity by construction, not by inference. This replaces a
 * heuristic that guessed a key from "the scalar AUTO-produced fields": that guess was
 * written into a class the moment it became a statement class, rewritten when an
 * unrelated field was edited, and offered behind a "Re-derive identity" button — so the
 * key in the editor was never a decision anyone made.
 *
 * <p>The structural part is not sufficient, and deliberately does not pretend to be.
 * Wikidata allows the same triple more than once, separated by qualifiers: History holds
 * 179 office holdings over 173 distinct subject/object pairs, because six people held
 * the same office twice and only the dates tell those records apart. WHICH qualifiers
 * complete the key is a modelling judgement, and it stays with the modeller — the
 * collision report is what says when the structural part was not enough.
 *
 * <p>One exception, and the shipped models are why it exists. A subject that arrives as
 * a participants COLLECTION has no single value to key on, so it contributes nothing —
 * Nobel's key is category + year + motivation and correctly omits its laureates list.
 */
public final class StatementIdentity {
    private StatementIdentity() { }

    /**
     * The part of the key that follows from the triple: the object, and the subject
     * wherever it lands in a single scalar field.
     */
    public static List<String> structuralKey(GeneratedClassModel owner) {
        List<String> key = new ArrayList<>();
        if (owner == null || !owner.reifiesStatements()) return key;

        for (GeneratedFieldModel field : owner.fields()) {
            if (field == null || field.name() == null || field.name().isBlank()) continue;
            if (!StatementFieldSemantics.receivesStatementSubject(owner, field)) continue;
            // A collection has no single value to key on. Excluding it is what keeps
            // Nobel's identity what its modeller chose.
            if (field.cardinality() == FieldCardinality.COLLECTION) continue;
            key.add(field.name());
        }

        String object = StatementFieldSemantics.statementValueFieldName(owner);
        if (!object.isBlank() && !key.contains(object)) {
            GeneratedFieldModel field = owner.fields().stream()
                    .filter(candidate -> candidate != null
                            && object.equals(candidate.name()))
                    .findFirst().orElse(null);
            // Same reason a participants collection is excluded: a list has no single
            // value to compare. A DATE object is kept — it is still the object, and
            // what type it happens to be does not change its place in the tuple.
            if (field != null && field.cardinality() != FieldCardinality.COLLECTION) {
                key.add(object);
            }
        }
        return key;
    }

    /**
     * The key the triple implies, OFFERED for a class that has none.
     *
     * <p>Offering and writing are different acts, and the difference is the whole point.
     * This used to write: on class creation, and again from the field editor whenever an
     * unrelated edit happened to leave the key equal to a fresh suggestion. So a class
     * carried an identity nobody had chosen, and no one could say what the key in the
     * editor meant. Nothing writes a key now — a modeller accepts this proposal, or
     * configures something else, and an empty key is a validation error rather than an
     * invitation to guess.
     *
     * @return the proposal, or empty when there is nothing to propose or a key already
     *         exists — in both cases there is nothing to offer
     */
    public static List<String> proposedKey(GeneratedClassModel owner) {
        if (owner == null || !owner.reifiesStatements()
                || !owner.canonical().keyFields().isEmpty()) {
            return List.of();
        }
        return structuralKey(owner);
    }
}
