package quiz;

import objectview.Viewable;
import objectview.ViewableAdapter;
import objectview.viewconfig.ViewConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Selecting an answer marks and judges the same things as before, without scanning.
 *
 * <p>Marking an answer used scanned every answer key on each click; the keys each
 * instance shows are now indexed while the quiz is built. This replays random click
 * sequences against the old scan and requires the same exhaustion state after every
 * click. Judging a choice compared instance names, so a distractor sharing a name with
 * a correct answer's instance counted as correct; it now compares instances.
 */
class QuizSelectionEquivalenceTest {

    @Test void markingAnswersUsedLeavesTheStateTheOldScanLeft() {
        Random random = new Random(20261005L);
        for (int domain = 0; domain < 200; domain++) {
            Map<String, Item> items = new LinkedHashMap<>();
            for (int i = 0; i < 1 + random.nextInt(8); i++) {
                String id = "d" + domain + "i" + i;
                items.put(id, new Item(id, id, values(random, "q", 4), values(random, "a", 5)));
            }
            Selections quiz = new Selections(items);
            try {
                Map<List<Object>, Integer> usage = new HashMap<>();
                Set<List<Object>> exhausted = new HashSet<>();
                List<Viewable> clickable = new ArrayList<>(items.values());
                for (int click = 0; click < 30; click++) {
                    Viewable choice = clickable.get(random.nextInt(clickable.size()));
                    quiz.mark(choice);
                    oldMark(quiz, choice, usage, exhausted);
                    assertEquals(usage, quiz.usage(), "domain " + domain + " click " + click);
                    assertEquals(exhausted, quiz.exhausted(),
                            "domain " + domain + " click " + click);
                }
            } finally {
                quiz.dispose();
            }
        }
    }

    /** The pre-index markAnswerAsUsed, kept verbatim as the reference. */
    private static void oldMark(Selections quiz, Viewable choice,
                                Map<List<Object>, Integer> usage, Set<List<Object>> exhausted) {
        for (Map.Entry<List<Object>, Viewable> e : quiz.answerInstances().entrySet()) {
            if (e.getValue().equals(choice)) {
                List<Object> key = e.getKey();
                int used = usage.getOrDefault(key, 0) + 1;
                usage.put(key, used);
                int allowed = quiz.useCounts().getOrDefault(key, 1);
                if (used >= allowed) exhausted.add(key);
            }
        }
    }

    @Test void aDistractorSharingANameWithTheCorrectInstanceIsNotCorrect() {
        Map<String, Item> items = new LinkedHashMap<>();
        Item correct = new Item("one", "Same", List.of("q1"), List.of("a1"));
        Item namesake = new Item("two", "Same", List.of("q2"), List.of("a2"));
        items.put("one", correct);
        items.put("two", namesake);
        items.put("three", new Item("three", "Other", List.of("q3"), List.of("a3")));
        QuizListABCD quiz = new QuizListABCD(selected("questions"), selected("answers"),
                QuizAnswerType.ABCD, null, items);
        try {
            assertTrue(quiz.isCorrectChoice(List.of("q1"), correct));
            assertFalse(quiz.isCorrectChoice(List.of("q1"), namesake),
                    "the same name is not the same instance");
        } finally {
            quiz.frame.dispose();
        }
    }

    private static List<String> values(Random random, String prefix, int alphabet) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < random.nextInt(4); i++) out.add(prefix + random.nextInt(alphabet));
        return out;
    }

    private static ViewConfig selected(String field) {
        ViewConfig config = ViewConfig.of(Item.class);
        config.setAllFields(false);
        config.addField(field, ViewConfig.leaf());
        return config;
    }

    private static final class Selections extends Quiz {
        Selections(Map<String, Item> items) {
            super(selected("questions"), selected("answers"), null, items);
        }

        @Override public void run() { }

        void mark(Viewable choice) { markAnswerAsUsed(choice); }
        Map<List<Object>, Integer> usage() { return exhaustionUsage; }
        Set<List<Object>> exhausted() { return exhaustedAnswers; }
        Map<List<Object>, Viewable> answerInstances() { return answerViewables; }
        Map<List<Object>, Integer> useCounts() { return correctAnswerUseCount; }
        void dispose() { if (frame != null) frame.dispose(); }
    }

    @SuppressWarnings("unused")
    private static final class Item extends ViewableAdapter {
        private final String id;
        private final String name;
        private final List<String> questions;
        private final List<String> answers;

        Item(String id, String name, List<String> questions, List<String> answers) {
            this.id = id;
            this.name = name;
            this.questions = questions;
            this.answers = answers;
        }

        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return name; }
        @Override public String getName() { return name; }
    }
}
