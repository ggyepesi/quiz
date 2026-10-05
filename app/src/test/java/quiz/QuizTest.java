package quiz;

import objectview.Viewable;
import objectview.ViewableAdapter;
import objectview.viewconfig.ViewConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;
import quiz.group.ViewableGroup;

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

        ViewConfig queryConfig = ViewConfig.of(TestCard.class);
        queryConfig.setAllFields(false);
        queryConfig.addField("queries", ViewConfig.leaf());

        ViewConfig answerConfig = ViewConfig.of(TestCard.class);
        answerConfig.setAllFields(false);
        answerConfig.addField("answers", ViewConfig.leaf());

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

    @Test
    void oneInstancesAnswerSetIsSharedAcrossItsManyQuestionAlternatives() {
        List<String> queries = java.util.stream.IntStream.range(0, 1_000)
                .mapToObj(i -> "q" + i).toList();
        List<String> answers = java.util.stream.IntStream.range(0, 1_000)
                .mapToObj(i -> "a" + i).toList();
        Map<String, TestCard> cards = Map.of(
                "large", new TestCard("large", queries, answers));

        ViewConfig queryConfig = ViewConfig.of(TestCard.class);
        queryConfig.setAllFields(false);
        queryConfig.addField("queries", ViewConfig.leaf());
        ViewConfig answerConfig = ViewConfig.of(TestCard.class);
        answerConfig.setAllFields(false);
        answerConfig.addField("answers", ViewConfig.leaf());

        TestQuiz quiz = new TestQuiz(queryConfig, answerConfig, null, cards);

        assertEquals(1_000, quiz.questionCount());
        assertEquals(1_000, quiz.answerCountFor("q0"));
        assertSame(quiz.answersFor("q0"), quiz.answersFor("q999"),
                "the index must not retain one million question-answer entries");
        assertEquals(1_000, quiz.allowedUsesOf("a0"),
                "sharing storage must preserve exhaustion counts");
    }

    @Test
    void aPersonWithSpousesAndOfficesProducesUsableAbcdPairs() {
        NamedEntity spouse = new NamedEntity("Q1", "Anne");
        NamedEntity office = new NamedEntity("Q2", "Queen regnant");
        HistoricalPerson person = new HistoricalPerson(
                "Q3", List.of(spouse), List.of(office));
        Map<String, HistoricalPerson> people = Map.of(person.getIdentifier(), person);

        ViewConfig queryConfig = ViewConfig.of(HistoricalPerson.class);
        queryConfig.setAllFields(false);
        queryConfig.addField("spouse", ViewConfig.leaf());
        ViewConfig answerConfig = ViewConfig.of(HistoricalPerson.class);
        answerConfig.setAllFields(false);
        answerConfig.addField("offices", ViewConfig.leaf());

        TestQuiz quiz = new TestQuiz(queryConfig, answerConfig, null, people);

        assertEquals(Set.of("Anne -> Queen regnant"), quiz.generatedPairs());
    }

    @Test
    void reportsAnEmptyFieldIndexInsteadOfOpeningAnAlreadyCompletedQuiz() {
        Map<String, TestCard> cards = new LinkedHashMap<>();
        cards.put("one", new TestCard("one", List.of(), List.of("a1")));
        cards.put("two", new TestCard("two", List.of(), List.of("a2")));

        ViewConfig queryConfig = ViewConfig.of(TestCard.class);
        queryConfig.setAllFields(false);
        queryConfig.addField("queries", ViewConfig.leaf());
        ViewConfig answerConfig = ViewConfig.of(TestCard.class);
        answerConfig.setAllFields(false);
        answerConfig.addField("answers", ViewConfig.leaf());

        TestQuiz quiz = new TestQuiz(queryConfig, answerConfig, null, cards);

        assertEquals(
                "No quiz items have values for every selected query and answer field. "
                        + "Select fields that contain values in this class.",
                quiz.prepareQuiz());
    }

    @Test
    void questionAndAnswerFieldsMustBeDisjointByTheirCompletePaths() {
        assertEquals(
                "Question and answer fields must be disjoint. Used on both sides: "
                        + "name, office.holder.name.",
                Quiz.disjointFieldProblem(
                        List.of(
                                objectview.field.FieldPath.of("name"),
                                objectview.field.FieldPath.parse("office.holder.name"),
                                objectview.field.FieldPath.parse("person.name")),
                        List.of(
                                objectview.field.FieldPath.of("name"),
                                objectview.field.FieldPath.parse("office.holder.name"),
                                objectview.field.FieldPath.parse("position.name"))));
        assertNull(Quiz.disjointFieldProblem(
                List.of(objectview.field.FieldPath.parse("person.name")),
                List.of(objectview.field.FieldPath.parse("position.name"))),
                "equal leaf names under different owners are different fields");
    }

    @Test
    void aDirectQuizCannotBypassTheDisjointFieldRule() {
        Map<String, TestCard> cards = new LinkedHashMap<>();
        cards.put("one", new TestCard("one", List.of("q1"), List.of("a1")));
        cards.put("two", new TestCard("two", List.of("q2"), List.of("a2")));
        ViewConfig bothSides = ViewConfig.of(TestCard.class);
        bothSides.setAllFields(false);
        bothSides.addField("name", ViewConfig.leaf());

        TestQuiz quiz = new TestQuiz(bothSides, bothSides, null, cards);

        assertEquals(
                "Question and answer fields must be disjoint. Used on both sides: name.",
                quiz.prepareQuiz());
    }

    private static class TestQuiz extends Quiz {
        TestQuiz(ViewConfig queryConfig,
                 ViewConfig answerConfig,
                 ViewableGroup group,
                 Map<String, ? extends Viewable> viewables) {
            super(queryConfig, answerConfig, group, viewables);
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

        int questionCount() {
            return answersToQuery.size();
        }

        int answerCountFor(String query) {
            List<List<Object>> answers = answersFor(query);
            return answers == null ? 0 : answers.size();
        }

        List<List<Object>> answersFor(String query) {
            return answersToQuery.get(List.of(query));
        }

        int allowedUsesOf(String answer) {
            return correctAnswerUseCount.getOrDefault(List.of(answer), 0);
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
    private static class TestCard extends ViewableAdapter {
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

    }

    @SuppressWarnings("unused")
    private static final class HistoricalPerson extends ViewableAdapter {
        private final String id;
        private final List<NamedEntity> spouse;
        private final List<NamedEntity> offices;

        HistoricalPerson(
                String id, List<NamedEntity> spouse, List<NamedEntity> offices) {
            this.id = id;
            this.spouse = spouse;
            this.offices = offices;
        }

        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return id; }
    }

    private static final class NamedEntity extends ViewableAdapter {
        private final String id;
        private final String name;

        NamedEntity(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return name; }
    }
}
