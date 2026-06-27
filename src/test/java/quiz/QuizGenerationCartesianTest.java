package quiz;

import org.junit.jupiter.api.Test;
import quiz.ui.viewconfig.QuizablePanelConfig;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class QuizGenerationCartesianTest {

    @Test
    void collectionQueryAndCollectionAnswerGenerateCartesianProduct() {
        Map<String, TestItem> items = new LinkedHashMap<>();
        items.put("x", new TestItem(
                "x",
                List.of("q1", "q2"),
                List.of("a1", "a2", "a3")));

        QuizablePanelConfig queryConfig = QuizablePanelConfig.of(TestItem.class);
        queryConfig.setAllFields(false);
        queryConfig.addField("queries", QuizablePanelConfig.leaf());

        QuizablePanelConfig answerConfig = QuizablePanelConfig.of(TestItem.class);
        answerConfig.setAllFields(false);
        answerConfig.addField("answers", QuizablePanelConfig.leaf());

        TestQuiz quiz = new TestQuiz(queryConfig, answerConfig, null, items);

        assertEquals(Set.of(
                "q1 -> a1",
                "q1 -> a2",
                "q1 -> a3",
                "q2 -> a1",
                "q2 -> a2",
                "q2 -> a3"
        ), quiz.generatedPairs());
    }

    @Test
    void multipleCollectionQueryFieldsGenerateCartesianProduct() {
        Map<String, TestItem> items = new LinkedHashMap<>();
        items.put("x", new TestItem(
                "x",
                List.of("q1", "q2"),
                List.of("lang1", "lang2"),
                List.of("a")));

        QuizablePanelConfig queryConfig = QuizablePanelConfig.of(TestItem.class);
        queryConfig.setAllFields(false);
        queryConfig.addField("queries", QuizablePanelConfig.leaf());
        queryConfig.addField("languages", QuizablePanelConfig.leaf());

        QuizablePanelConfig answerConfig = QuizablePanelConfig.of(TestItem.class);
        answerConfig.setAllFields(false);
        answerConfig.addField("answers", QuizablePanelConfig.leaf());

        TestQuiz quiz = new TestQuiz(queryConfig, answerConfig, null, items);

        assertEquals(Set.of(
                "[q1, lang1] -> a",
                "[q1, lang2] -> a",
                "[q2, lang1] -> a",
                "[q2, lang2] -> a"
        ), quiz.generatedPairs());
    }

    @Test
    void emptyCollectionDoesNotGenerateQueryOrAnswer() {
        Map<String, TestItem> items = new LinkedHashMap<>();
        items.put("emptyQuery", new TestItem("emptyQuery", List.of(), List.of("a1")));
        items.put("emptyAnswer", new TestItem("emptyAnswer", List.of("q1"), List.of()));

        QuizablePanelConfig queryConfig = QuizablePanelConfig.of(TestItem.class);
        queryConfig.setAllFields(false);
        queryConfig.addField("queries", QuizablePanelConfig.leaf());

        QuizablePanelConfig answerConfig = QuizablePanelConfig.of(TestItem.class);
        answerConfig.setAllFields(false);
        answerConfig.addField("answers", QuizablePanelConfig.leaf());

        TestQuiz quiz = new TestQuiz(queryConfig, answerConfig, null, items);

        assertTrue(quiz.generatedPairs().isEmpty());
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
                String q = keyToString(e.getKey());

                for (List<Object> answerKey : e.getValue()) {
                    out.add(q + " -> " + keyToString(answerKey));
                }
            }

            return out;
        }

        private String keyToString(List<Object> key) {
            if (key == null || key.isEmpty()) return "";
            if (key.size() == 1) return String.valueOf(key.get(0));
            return key.toString();
        }
    }

    @SuppressWarnings("unused")
    private static class TestItem extends QuizableAdapter {
        private final String name;
        private final List<String> queries;
        private final List<String> languages;
        private final List<String> answers;

        TestItem(String name, List<String> queries, List<String> answers) {
            this(name, queries, List.of(), answers);
        }

        TestItem(String name,
                 List<String> queries,
                 List<String> languages,
                 List<String> answers) {
            this.name = name;
            this.queries = queries;
            this.languages = languages;
            this.answers = answers;
        }

        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }

        @Override
        public QuizableAdapter createNew() {
            return new TestItem("", List.of(), List.of(), List.of());
        }
    }
}