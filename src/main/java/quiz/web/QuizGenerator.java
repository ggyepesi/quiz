package quiz.web;

import quiz.Quizable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

/**
 * Builds a multiple-choice {@link Quiz} from a dataset: each question shows
 * the {@code promptFields} of one Quizable (e.g. its logo, or name + group)
 * and asks for the combination of its {@code answerFields}, with distractors
 * drawn from other items.
 */
public final class QuizGenerator {

    private static final Random R = new Random();
    private static final int OPTIONS = 4;
    private static final String JOIN = " · ";

    private QuizGenerator() {}

    public static Quiz generate(
            QuizableStore store,
            String type,
            String group,
            List<String> promptFields,
            List<String> answerFields,
            int n) throws Exception {

        Collection<Quizable> all = store.members(type, group);
        if (all == null || promptFields.isEmpty() || answerFields.isEmpty()) {
            return new Quiz(type, join(promptFields), join(answerFields), List.of());
        }

        record Entry(List<QuizableView.Field> prompts, String answer) {}

        List<Entry> entries = new ArrayList<>();
        LinkedHashSet<String> answerPool = new LinkedHashSet<>();

        for (Quizable q : all) {
            List<QuizableView.Field> prompts = new ArrayList<>();
            for (String pf : promptFields) {
                QuizableView.Field fv = QuizableJson.fieldOf(q, pf);
                if (fv != null) {
                    prompts.add(blurredIfNeeded(type, q, pf, fv));
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

            String answer = String.join(JOIN, answerParts);
            entries.add(new Entry(prompts, answer));
            answerPool.add(answer);
        }

        List<String> pool = new ArrayList<>(answerPool);
        Collections.shuffle(entries, R);

        int count = Math.min(n, entries.size());
        List<Quiz.Question> questions = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            Entry e = entries.get(i);
            questions.add(new Quiz.Question(e.prompts(), options(e.answer(), pool), e.answer()));
        }

        return new Quiz(type, join(promptFields), join(answerFields), questions);
    }

    private static List<String> options(String correct, List<String> pool) {
        LinkedHashSet<String> opts = new LinkedHashSet<>();
        opts.add(correct);

        List<String> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, R);
        for (String s : shuffled) {
            if (opts.size() >= OPTIONS) {
                break;
            }
            opts.add(s);
        }

        List<String> list = new ArrayList<>(opts);
        Collections.shuffle(list, R);
        return list;
    }

    private static String join(List<String> fields) {
        return String.join(", ", fields);
    }

    // For datasets whose image embeds the answer (e.g. a constellation chart
    // labelled with its name), point the prompt at the name-blurring endpoint.
    static QuizableView.Field blurredIfNeeded(
            String type, Quizable q, String field, QuizableView.Field fv) {
        // Route through the blur endpoint when this entity actually has
        // something to hide: a hand mask, or it's a runtime-OCR type.
        if ("image".equals(fv.kind())
                && quiz.ocr.QuizImageBlurrer.blurs(type, q.getDisplayName())) {
            return QuizableView.Field.image(
                    fv.name(), BlurredImageService.blurUrl(type, q.getIdentifier(), field));
        }
        return fv;
    }
}
