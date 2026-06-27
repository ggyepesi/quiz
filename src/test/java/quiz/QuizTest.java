package quiz;

import org.junit.jupiter.api.Test;
import quiz.ui.viewconfig.QuizablePanelConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

class QuizGenerationTest {

    @Test
    void generatesAllValidQueryAnswerCombinationsAndSkipsEmptyValues() {
        Map<String, TestCard> cards = new LinkedHashMap<>();

        cards.put("one", new TestCard("one",
                List.of("q1", "q2"),
                List.of("a1", "a2")));

        cards.put("two", new TestCard("two",
                List.of("q1"),
                List.of("a3")));

        cards.put("emptyQuery", new TestCard("emptyQuery",
                List.of(),
                List.of("a4")));

        cards.put("emptyAnswer", new TestCard("emptyAnswer",
                List.of("q3"),
                List.of()));

        QuizablePanelConfig queryConfig = QuizablePanelConfig.of(TestCard.class);
        queryConfig.setAllFields(false);
        queryConfig.addField("queries", QuizablePanelConfig.leaf());

        QuizablePanelConfig answerConfig = QuizablePanelConfig.of(TestCard.class);
        answerConfig.setAllFields(false);
        answerConfig.addField("answers", QuizablePanelConfig.leaf());

        TestQuiz quiz = new TestQuiz(queryConfig, answerConfig, null, cards);

        assertEquals(Set.of(
                "q1 -> a1",
                "q1 -> a2",
                "q2 -> a1",
                "q2 -> a2",
                "q1 -> a3"
        ), quiz.generatedPairs());

        assertFalse(quiz.generatedPairs().contains("q3 -> "));
        assertFalse(quiz.generatedPairs().contains(" -> a4"));
    }

    private static class TestQuiz extends Quiz {
        TestQuiz(QuizablePanelConfig queryConfig,
                 QuizablePanelConfig answerConfig,
                 QuizableGroup group,
                 Map<String, ? extends Quizable> quizables) {
            super(queryConfig, answerConfig, group, quizables);
        }

        @Override
        public void run() {
        }

        Set<String> generatedPairs() {
            Set<String> out = new TreeSet<>();

            for (Map.Entry<List<Object>, List<List<Object>>> e : answersToQuery.entrySet()) {
                String query = keyToString(e.getKey());

                for (List<Object> answerKey : e.getValue()) {
                    out.add(query + " -> " + keyToString(answerKey));
                }
            }

            return out;
        }

        private String keyToString(List<Object> key) {
            if (key == null || key.isEmpty()) {
                return "";
            }

            if (key.size() == 1) {
                return String.valueOf(key.get(0));
            }

            return key.toString();
        }
    }

    @SuppressWarnings("unused")
    private static class TestCard extends QuizableAdapter {
        private final String name;
        private final List<String> queries;
        private final List<String> answers;

        TestCard(String name, List<String> queries, List<String> answers) {
            this.name = name;
            this.queries = queries;
            this.answers = answers;
        }

        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }

        @Override
        public QuizableAdapter createNew() {
            return new TestCard("", List.of(), List.of());
        }
    }
}