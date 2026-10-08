package quiz;

import objectview.Viewable;
import objectview.ViewableAdapter;
import objectview.viewconfig.ViewConfig;
import objectview.field.FieldPath;
import org.junit.jupiter.api.Test;
import quiz.data.ViewableKeyExtractor;
import quiz.ui.ChoiceBoard;
import quiz.ui.QuizCardRole;
import quiz.ui.SearchableChoiceBoard;
import objectview.render.Card;

import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.GridBagLayout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void listSearchSortAndViewStartWithTheAnswerConfiguration() {
        QuizListABCD quiz = quiz(QuizAnswerType.LIST);

        SearchableChoiceBoard board =
                quiz.createListAnswerBoard(List.of("q1"));
        var state = board.search().configState();
        ViewableKeyExtractor extractor = new ViewableKeyExtractor();
        List<FieldPath> expected = List.of(FieldPath.of("answer"));

        assertEquals(expected, extractor.paths(state.search()));
        assertEquals(expected, extractor.paths(state.sort()));
        assertEquals(expected, extractor.paths(state.view()));
        quiz.frame.dispose();
    }

    @Test
    void listAnswerBoardKeepsSyntheticDisplayAsTheCaption() {
        Map<String, Item> items = new LinkedHashMap<>();
        items.put("one", new Item("one", "q1", "a1"));
        items.put("two", new Item("two", "q2", "a2"));
        ViewConfig display = ViewConfig.of(Item.class);
        display.setAllFields(false);
        display.addField(
                objectview.field.ViewableContractFieldSet.DISPLAY_KEY,
                ViewConfig.leaf());
        QuizListABCD quiz = new QuizListABCD(
                selected("question"), display, QuizAnswerType.LIST,
                null, items);
        objectview.field.FieldSchema sourceTypeSchema =
                () -> new objectview.field.ReflectionFieldSet(items.get("one")).fields();
        quiz.setSchemas(ignored -> sourceTypeSchema, ignored -> sourceTypeSchema);

        SearchableChoiceBoard board = quiz.createListAnswerBoard(List.of("q1"));
        ChoiceBoard.CardItem item = quiz.answerCardItem(List.of("one"));
        assertNotNull(item);
        Card rendered = quiz.cardFactory.create(
                item.content(), board.search().getViewConfig(),
                QuizCardRole.OPTION, List.of(item.content()), item.reveal());

        assertEquals("one", rendered.getTitle());
        assertFalse(rendersText(rendered,
                        objectview.field.ViewableContractFieldSet.DISPLAY_KEY),
                "the List answer board must not turn the display address into a body field");
        quiz.frame.dispose();
    }

    @Test
    void listKeepsSearchControlsCompactAndGivesTheScrollableQueryLessHeight() throws Exception {
        QuizListABCD quiz = quiz(QuizAnswerType.LIST);
        javax.swing.JComponent[] content = new javax.swing.JComponent[1];
        SwingUtilities.invokeAndWait(() ->
                content[0] = quiz.createRoundContent(List.of("q1")));

        Component[] sections = content[0].getComponents();
        assertEquals(2, sections.length);
        JScrollPane query = (JScrollPane) sections[0];
        assertTrue(query.getViewport().getView() instanceof Card,
                "the query card must have its own scroll boundary");
        SearchableChoiceBoard answers = (SearchableChoiceBoard) sections[1];
        assertTrue(!answers.controlsExpanded(),
                "Search / sort / view starts collapsed so answers remain visible");

        GridBagLayout layout = (GridBagLayout) content[0].getLayout();
        double queryWeight = layout.getConstraints(query).weighty;
        double answerWeight = layout.getConstraints(answers).weighty;
        assertTrue(answerWeight > queryWeight,
                "the answer browser must receive more height than the query");
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

    private static boolean rendersText(java.awt.Container root, String expected) {
        for (java.awt.Component component : root.getComponents()) {
            if (component instanceof objectview.render.TextRow row
                    && row.matchesRenderedText(List.of(expected), true)) return true;
            if (component instanceof javax.swing.JLabel label
                    && expected.equals(label.getText())) return true;
            if (component instanceof java.awt.Container child
                    && rendersText(child, expected)) return true;
        }
        return false;
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
