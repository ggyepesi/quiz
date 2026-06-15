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
 * the {@code promptField} of one Quizable (e.g. its logo) and asks for its
 * {@code answerField} value, with distractors drawn from other items.
 */
public final class QuizGenerator {

    private static final Random R = new Random();
    private static final int OPTIONS = 4;

    private QuizGenerator() {}

    public static Quiz generate(
            QuizableStore store,
            String type,
            String group,
            String promptField,
            String answerField,
            int n) throws Exception {

        Collection<Quizable> all = store.members(type, group);
        if (all == null) {
            return new Quiz(type, promptField, answerField, List.of());
        }

        record Entry(QuizableView.Field prompt, String answer) {}

        List<Entry> entries = new ArrayList<>();
        LinkedHashSet<String> answerPool = new LinkedHashSet<>();

        for (Quizable q : all) {
            QuizableView.Field prompt = QuizableJson.fieldOf(q, promptField);
            String answer = QuizableJson.stringValue(q, answerField);

            if (prompt != null && answer != null) {
                entries.add(new Entry(prompt, answer));
                answerPool.add(answer);
            }
        }

        List<String> pool = new ArrayList<>(answerPool);
        Collections.shuffle(entries, R);

        int count = Math.min(n, entries.size());
        List<Quiz.Question> questions = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            Entry e = entries.get(i);
            questions.add(new Quiz.Question(e.prompt(), options(e.answer(), pool), e.answer()));
        }

        return new Quiz(type, promptField, answerField, questions);
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
}
