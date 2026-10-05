package quiz;

import objectview.Viewable;
import objectview.ViewableAdapter;
import objectview.viewconfig.ViewConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ABCD chooses four cards once. Removing exhausted keys afterwards made later
 * rounds visibly shrink, even though those answers remained valid distractors. */
class QuizListABCDTest {

    @Test
    void exhaustedAnswersRemainAvailableAsDistractorsInFourChoiceRounds() {
        QuizListABCD quiz = quiz(QuizAnswerType.ABCD);
        quiz.exhaustedAnswers.add(List.of("a2"));
        quiz.exhaustedAnswers.add(List.of("a3"));

        List<Viewable> choices = quiz.buildAnswerOptions(List.of("q1"));

        assertEquals(4, choices.size());
        assertEquals(4, choices.stream().map(Viewable::getIdentifier).distinct().count());
        assertTrue(choices.stream().anyMatch(item -> "two".equals(item.getIdentifier())),
                "an exhausted answer remains usable as a distractor");
        assertEquals(1, choices.stream()
                .filter(item -> "one".equals(item.getIdentifier())).count(),
                "the current question still has exactly one correct choice");
        quiz.frame.dispose();
    }

    @Test
    void listUsesTheSameOptionConstructionAndMarksRatherThanRemovesExhaustedAnswers() {
        QuizListABCD quiz = quiz(QuizAnswerType.LIST);
        quiz.exhaustedAnswers.add(List.of("a2"));

        List<Viewable> choices = quiz.buildAnswerOptions(List.of("q1"));

        assertEquals(4, choices.size());
        assertTrue(choices.stream().anyMatch(item -> "two".equals(item.getIdentifier())));
        quiz.frame.dispose();
    }

    private static QuizListABCD quiz(QuizAnswerType type) {
        Map<String, Item> items = new LinkedHashMap<>();
        items.put("one", new Item("one", "q1", "a1"));
        items.put("two", new Item("two", "q2", "a2"));
        items.put("three", new Item("three", "q3", "a3"));
        items.put("four", new Item("four", "q4", "a4"));
        return new QuizListABCD(
                selected("question"), selected("answer"), type, null, items);
    }

    private static ViewConfig selected(String field) {
        ViewConfig config = ViewConfig.of(Item.class);
        config.setAllFields(false);
        config.addField(field, ViewConfig.leaf());
        return config;
    }

    @SuppressWarnings("unused")
    private static final class Item extends ViewableAdapter {
        private final String id;
        private final String question;
        private final String answer;

        private Item(String id, String question, String answer) {
            this.id = id;
            this.question = question;
            this.answer = answer;
        }

        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return id; }
    }
}
