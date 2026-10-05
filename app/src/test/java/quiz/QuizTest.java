package quiz;

import objectview.Viewable;
import objectview.ViewableAdapter;
import objectview.field.ViewableContractFieldSet;
import objectview.render.Card;
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
    void nestedQuestionRendersOnlyTheCollectionMemberThatProducedItsKey() {
        Position president = new Position("P1", "President");
        Position deputy = new Position("P2", "Member of the Congress of Deputies");
        QuizPerson person = new QuizPerson("Q1", "Narcís Verdaguer i Callís",
                List.of(
                        new OfficeHolding("H1", personName(), president),
                        new OfficeHolding("H2", personName(), deputy)),
                "answer");

        ViewConfig positionConfig = ViewConfig.of(Position.class);
        positionConfig.setAllFields(false);
        positionConfig.addField(
                ViewableContractFieldSet.DISPLAY_KEY, ViewConfig.leaf());
        ViewConfig officeConfig = ViewConfig.of(OfficeHolding.class);
        officeConfig.setAllFields(false);
        officeConfig.addField("position", positionConfig);
        ViewConfig queryConfig = ViewConfig.of(QuizPerson.class);
        queryConfig.setAllFields(false);
        queryConfig.addField("offices", officeConfig);
        ViewConfig answerConfig = ViewConfig.of(QuizPerson.class);
        answerConfig.setAllFields(false);
        answerConfig.addField("answer", ViewConfig.leaf());

        TestQuiz quiz = new TestQuiz(queryConfig, answerConfig, null,
                Map.of(person.getIdentifier(), person));

        Card prompt = quiz.questionCard("President");
        assertNotNull(prompt);
        assertNotSame(person, prompt.getViewable(),
                "ObjectView must receive the assembled query tuple, not the complete Person");
        assertSame(queryConfig, quiz.questionViewConfig("President"),
                "selection and rendering must use the same ViewConfig");
        assertEquals("QuizPerson", prompt.getViewable().typeName());
        assertEquals("", prompt.getTitle());
        assertEquals(List.of("President"), new quiz.data.ViewableKeyExtractor()
                .alternatives(prompt.getViewable(),
                        "offices.position.@view:display"));
        assertTrue(rendersText(prompt, "President"), () -> renderTree(prompt, ""));
        assertFalse(rendersText(prompt, person.getDisplayName()));
    }

    @Test
    void selectedReferenceDisplayRendersThePositionRatherThanItsHoldingOwner() {
        Position president = new Position("P1", "President");
        QuizPerson person = new QuizPerson("Q1", personName(),
                List.of(new OfficeHolding("H1", personName(), president)),
                "answer");

        ViewConfig officeConfig = ViewConfig.of(OfficeHolding.class);
        officeConfig.setAllFields(false);
        // This bare reference is the editor's saved shorthand for its display label.
        officeConfig.addField("position", ViewConfig.leaf());
        ViewConfig queryConfig = ViewConfig.of(QuizPerson.class);
        queryConfig.setAllFields(false);
        queryConfig.addField("offices", officeConfig);
        ViewConfig answerConfig = ViewConfig.of(QuizPerson.class);
        answerConfig.setAllFields(false);
        answerConfig.addField("answer", ViewConfig.leaf());

        TestQuiz quiz = new TestQuiz(queryConfig, answerConfig, null,
                Map.of(person.getIdentifier(), person));

        Card prompt = quiz.questionCard("President");
        assertNotNull(prompt);
        assertNotSame(person, prompt.getViewable());
        assertEquals("QuizPerson", prompt.getViewable().typeName());
        assertEquals("", prompt.getTitle());
        assertEquals(List.of("President"), new quiz.data.ViewableKeyExtractor()
                .alternatives(prompt.getViewable(), "offices.position"));
        assertTrue(rendersText(prompt, "President"), () -> renderTree(prompt, ""));
        assertFalse(rendersText(prompt, person.getDisplayName()));
    }

    @Test
    void savedDomainReferenceDisplayAlsoRendersTheReferencedPosition() {
        quiz.transform.DynamicViewable position =
                new quiz.transform.DynamicViewable("P1", "President");
        quiz.transform.DynamicViewable holding =
                new quiz.transform.DynamicViewable("H1", personName());
        holding.put("position", position);
        quiz.transform.DynamicViewable person =
                new quiz.transform.DynamicViewable("Q1", personName());
        person.put("offices", List.of(holding));
        person.put("answer", "answer");

        ViewConfig positionConfig = new ViewConfig();
        positionConfig.setAllFields(false);
        ViewConfig officeConfig = new ViewConfig();
        officeConfig.setAllFields(false);
        officeConfig.addField("position", positionConfig);
        ViewConfig queryConfig = new ViewConfig();
        queryConfig.setAllFields(false);
        queryConfig.addField("offices", officeConfig);
        ViewConfig answerConfig = new ViewConfig();
        answerConfig.setAllFields(false);
        answerConfig.addField("answer", ViewConfig.leaf());

        TestQuiz quiz = new TestQuiz(queryConfig, answerConfig, null,
                Map.of(person.getIdentifier(), person));

        Card prompt = quiz.questionCard("President");
        assertNotNull(prompt);
        assertNotSame(person, prompt.getViewable());
        assertEquals(person.typeName(), prompt.getViewable().typeName());
        assertEquals("", prompt.getTitle());
        assertEquals(List.of("President"), new quiz.data.ViewableKeyExtractor()
                .alternatives(prompt.getViewable(), "offices.position"));
        assertTrue(rendersText(prompt, "President"), () -> renderTree(prompt, ""));
        assertFalse(rendersText(prompt, person.getDisplayName()));
    }

    @Test
    void datesAndNestedPositionRenderOneAssembledQueryTuple() {
        Position president = new Position("P1", "President");
        Position deputy = new Position("P2", "Deputy");
        QuizPerson person = new QuizPerson("Q1", personName(),
                List.of(
                        new OfficeHolding("H1", personName(), president),
                        new OfficeHolding("H2", personName(), deputy)),
                "answer");

        ViewConfig positionConfig = ViewConfig.of(Position.class);
        positionConfig.setAllFields(false);
        positionConfig.addField(
                ViewableContractFieldSet.DISPLAY_KEY, ViewConfig.leaf());
        ViewConfig officeConfig = ViewConfig.of(OfficeHolding.class);
        officeConfig.setAllFields(false);
        officeConfig.addField("position", positionConfig);
        ViewConfig queryConfig = ViewConfig.of(QuizPerson.class);
        queryConfig.setAllFields(false);
        queryConfig.addField("dateOfBirth", ViewConfig.leaf());
        queryConfig.addField("dateOfDeath", ViewConfig.leaf());
        queryConfig.addField("offices", officeConfig);
        ViewConfig answerConfig = ViewConfig.of(QuizPerson.class);
        answerConfig.setAllFields(false);
        answerConfig.addField("answer", ViewConfig.leaf());

        TestQuiz quiz = new TestQuiz(queryConfig, answerConfig, null,
                Map.of(person.getIdentifier(), person));

        Card prompt = quiz.questionCard(List.of(
                "1800-01-01", "1864-01-01", "President"));
        assertNotNull(prompt);
        assertNotSame(person, prompt.getViewable(),
                "the rendered object is the selected tuple, not the complete Person");
        assertEquals("", prompt.getViewable().getDisplayName(),
                "an unselected owner display label is explicitly empty");
        assertEquals("1800-01-01", objectview.field.FieldAccess.getPathValues(
                prompt.getViewable(), objectview.field.FieldPath.of("dateOfBirth")));
        assertEquals(List.of("President"), new quiz.data.ViewableKeyExtractor()
                .alternatives(prompt.getViewable(), "offices.position"));
        assertEquals("", prompt.getTitle(),
                "the unselected Person/holding display label must not leak into the query");
    }

    @Test
    void nestedAnswerUsesTheSameSelectedObjectAndViewConfigPath() {
        Position president = new Position("P1", "President");
        Position deputy = new Position("P2", "Member of the Congress of Deputies");
        QuizPerson person = new QuizPerson("Q1", personName(),
                List.of(
                        new OfficeHolding("H1", personName(), president),
                        new OfficeHolding("H2", personName(), deputy)),
                "question");

        ViewConfig queryConfig = ViewConfig.of(QuizPerson.class);
        queryConfig.setAllFields(false);
        queryConfig.addField("answer", ViewConfig.leaf());
        ViewConfig positionConfig = ViewConfig.of(Position.class);
        positionConfig.setAllFields(false);
        positionConfig.addField(
                ViewableContractFieldSet.DISPLAY_KEY, ViewConfig.leaf());
        ViewConfig officeConfig = ViewConfig.of(OfficeHolding.class);
        officeConfig.setAllFields(false);
        officeConfig.addField("position", positionConfig);
        ViewConfig answerConfig = ViewConfig.of(QuizPerson.class);
        answerConfig.setAllFields(false);
        answerConfig.addField("offices", officeConfig);

        TestQuiz quiz = new TestQuiz(queryConfig, answerConfig, null,
                Map.of(person.getIdentifier(), person));

        Card answer = quiz.answerCard("President");
        assertNotNull(answer);
        assertNotSame(person, answer.getViewable());
        assertEquals("QuizPerson", answer.getViewable().typeName());
        assertEquals("", answer.getTitle());
        assertEquals(List.of("President"), new quiz.data.ViewableKeyExtractor()
                .alternatives(answer.getViewable(),
                        "offices.position.@view:display"));
        assertTrue(rendersText(answer, "President"), () -> renderTree(answer, ""));
        assertFalse(rendersText(answer, person.getDisplayName()));
    }

    @Test
    void selectingACollectionItselfKeepsTheWholeCollectionContentObject() {
        QuizPerson person = new QuizPerson("Q1", personName(),
                List.of(
                        new OfficeHolding("H1", "First holding",
                                new Position("P1", "President")),
                        new OfficeHolding("H2", "Second holding",
                                new Position("P2", "Deputy"))),
                "answer");
        ViewConfig queryConfig = ViewConfig.of(QuizPerson.class);
        queryConfig.setAllFields(false);
        queryConfig.addField("offices", ViewConfig.leaf());
        ViewConfig answerConfig = ViewConfig.of(QuizPerson.class);
        answerConfig.setAllFields(false);
        answerConfig.addField("answer", ViewConfig.leaf());

        TestQuiz quiz = new TestQuiz(queryConfig, answerConfig, null,
                Map.of(person.getIdentifier(), person));

        Card first = quiz.questionCard("First holding");
        Card second = quiz.questionCard("Second holding");
        assertNotSame(person, first.getViewable());
        assertNotSame(person, second.getViewable());
        assertEquals(List.of("First holding", "Second holding"),
                new quiz.data.ViewableKeyExtractor()
                        .alternatives(first.getViewable(), "offices"));
    }

    private static String personName() {
        return "Narcís Verdaguer i Callís";
    }

    private static boolean rendersText(java.awt.Container root, String expected) {
        for (java.awt.Component component : root.getComponents()) {
            if (component instanceof objectview.render.TextRow row
                    && row.matchesRenderedText(List.of(expected), true)) {
                return true;
            }
            if (component instanceof javax.swing.JLabel label
                    && expected.equals(label.getText())) {
                return true;
            }
            if (component instanceof java.awt.Container child
                    && rendersText(child, expected)) return true;
        }
        return false;
    }

    private static String renderTree(java.awt.Container root, String indent) {
        StringBuilder text = new StringBuilder();
        for (java.awt.Component component : root.getComponents()) {
            text.append(indent).append(component.getClass().getSimpleName());
            if (component instanceof javax.swing.JComponent swing) {
                Object value = swing.getClientProperty(
                        objectview.field.FieldProperties.FIELD_VALUE_PROPERTY);
                if (value != null) text.append(" value=").append(value);
            }
            text.append('\n');
            if (component instanceof java.awt.Container child) {
                text.append(renderTree(child, indent + "  "));
            }
        }
        return text.toString();
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

        Card questionCard(String key) {
            return createQueryPanel(List.of(key));
        }

        Card questionCard(List<Object> key) {
            return createQueryPanel(key);
        }

        ViewConfig questionViewConfig(String key) {
            return queryContents.get(List.of(key)).viewConfig();
        }

        Card answerCard(String key) {
            return createAnswerPanel(List.of(key), quiz.ui.QuizCardRole.OPTION);
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

    @SuppressWarnings("unused")
    private static final class QuizPerson extends ViewableAdapter {
        private final String id;
        private final String name;
        private final List<OfficeHolding> offices;
        private final String answer;
        private final String dateOfBirth = "1800-01-01";
        private final String dateOfDeath = "1864-01-01";

        QuizPerson(String id, String name, List<OfficeHolding> offices, String answer) {
            this.id = id;
            this.name = name;
            this.offices = offices;
            this.answer = answer;
        }

        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return name; }
    }

    @SuppressWarnings("unused")
    private static final class OfficeHolding extends ViewableAdapter {
        private final String id;
        private final String holderName;
        private final Position position;

        OfficeHolding(String id, String holderName, Position position) {
            this.id = id;
            this.holderName = holderName;
            this.position = position;
        }

        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return holderName; }
    }

    @SuppressWarnings("unused")
    private static final class Position extends ViewableAdapter {
        private final String id;
        private final String name;

        Position(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return name; }
    }
}
