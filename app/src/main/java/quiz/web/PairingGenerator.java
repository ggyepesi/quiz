package quiz.web;

import quiz.Quizable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Builds a {@link Pairing} matching game from a dataset: each item contributes
 * one pair of its {@code promptFields} (e.g. logo, or name) against the
 * combination of its {@code answerFields}. No distractors — every prompt has a
 * matching answer in the round. Mirrors {@link QuizGenerator}'s projection.
 */
public final class PairingGenerator {

    private static final Random R = new Random();
    private static final String JOIN = " · ";

    private PairingGenerator() {}

    public static Pairing generate(
            QuizableStore store,
            String type,
            String group,
            List<String> promptFields,
            List<String> answerFields,
            int n) throws Exception {

        // Drop a bare parent path when a child path under it is also selected
        // (e.g. "stars" + "stars.name" would render the names twice).
        promptFields = QuizGenerator.withoutCoveredParents(promptFields);
        answerFields = QuizGenerator.withoutCoveredParents(answerFields);

        Collection<Quizable> all = store.members(type, group);
        if (all == null || promptFields.isEmpty() || answerFields.isEmpty()) {
            return new Pairing(type, join(promptFields), join(answerFields), List.of());
        }

        List<Pairing.Pair> pairs = new ArrayList<>();
        for (Quizable q : all) {
            List<QuizableView.Field> prompts = new ArrayList<>();
            for (String pf : promptFields) {
                QuizableView.Field fv = QuizableJson.fieldOf(q, pf);
                if (fv != null) {
                    prompts.add(QuizGenerator.blurredIfNeeded(type, q, pf, fv));
                }
            }
            if (prompts.isEmpty()) {
                continue;
            }

            List<String> answerParts = new ArrayList<>();
            for (String af : answerFields) {
                String s = QuizableJson.stringValue(q, af);
                if (s != null) {
                    answerParts.add(s);
                }
            }
            if (answerParts.isEmpty()) {
                continue;
            }

            pairs.add(new Pairing.Pair(prompts, String.join(JOIN, answerParts)));
        }

        Collections.shuffle(pairs, R);
        if (pairs.size() > n) {
            pairs = new ArrayList<>(pairs.subList(0, n));
        }

        return new Pairing(type, join(promptFields), join(answerFields), pairs);
    }

    private static String join(List<String> fields) {
        return String.join(", ", fields);
    }
}
