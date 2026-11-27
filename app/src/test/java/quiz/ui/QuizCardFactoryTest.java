package quiz.ui;

import objectview.ViewableAdapter;
import objectview.field.FieldPath;
import objectview.field.FieldSet;
import objectview.field.LinkedTextSequence;
import objectview.render.TextRow;
import objectview.viewconfig.ViewConfig;
import org.junit.jupiter.api.Test;
import quiz.data.ViewableKeyExtractor;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuizCardFactoryTest {
    private final QuizCardFactory factory = new QuizCardFactory(List.of());
    private final Item item = new Item();

    @Test
    void promptUsesAFullSizeListenerFreeDefensiveCopy() {
        ViewConfig source = configWithNestedField();
        source.setThumb(true);
        source.getFieldConfig("detail").setThumb(true);
        source.setAddListener(true);

        ViewConfig result = factory.configurationFor(
                item, source, QuizCardPresentation.forRole(QuizCardRole.PROMPT));

        assertNotSame(source, result);
        assertFalse(result.isThumb());
        assertFalse(result.getFieldConfig("detail").isThumb());
        assertFalse(result.isAddListener());
        assertTrue(source.isThumb());
        assertTrue(source.getFieldConfig("detail").isThumb());
        assertTrue(source.isAddListener());
    }

    @Test
    void optionUsesThumbnailsWithoutMutatingTheSource() {
        ViewConfig source = configWithNestedField();
        source.setThumb(false);
        source.getFieldConfig("detail").setThumb(false);

        ViewConfig result = factory.configurationFor(
                item, source, QuizCardPresentation.forRole(QuizCardRole.OPTION));

        assertTrue(result.isThumb());
        assertFalse(result.getFieldConfig("detail").isThumb());
        assertFalse(source.isThumb());
        assertFalse(source.getFieldConfig("detail").isThumb());
    }

    @Test
    void pairingPreservesEditorMediaChoicesAndItsClasslessDomainConfig() {
        ViewConfig source = configWithNestedField();
        source.setCls(null);
        source.setThumb(true);
        source.getFieldConfig("detail").setThumb(false);

        ViewConfig result = factory.configurationFor(
                item, source,
                QuizCardPresentation.forRole(QuizCardRole.PAIR_ANSWER));

        assertNull(result.getCls());
        assertTrue(result.isThumb());
        assertFalse(result.getFieldConfig("detail").isThumb());
        assertNull(source.getCls());
    }

    @Test
    void missingConfigUsesTheRuntimeClass() {
        ViewConfig result = factory.configurationFor(
                item, null, QuizCardPresentation.forRole(QuizCardRole.PROMPT));

        assertEquals(Item.class, result.getCls());
    }

    @Test
    void aDynamicTupleKeepsItsClasslessSchemaConfiguration() {
        domain.transform.DynamicViewable dynamic =
                new domain.transform.DynamicViewable("Q1", "Dynamic");
        dynamic.put("realField", "value");
        ViewConfig source = new ViewConfig();
        source.setAllFields(true);

        ViewConfig result = factory.configurationFor(
                dynamic, source, QuizCardPresentation.forRole(QuizCardRole.PROMPT));

        assertNull(result.getCls(),
                "the runtime DynamicFields carrier is not the logical domain class");
    }

    @Test
    void rolesDescribeExistingDesktopFillAndContextPolicies() {
        QuizCardPresentation prompt =
                QuizCardPresentation.forRole(QuizCardRole.PROMPT);
        QuizCardPresentation option =
                QuizCardPresentation.forRole(QuizCardRole.OPTION);
        QuizCardPresentation pairAnswer =
                QuizCardPresentation.forRole(QuizCardRole.PAIR_ANSWER);

        assertTrue(prompt.fill());
        assertTrue(prompt.useQuizContext());
        assertFalse(option.fill());
        assertTrue(option.useQuizContext());
        assertFalse(pairAnswer.fill());
        assertFalse(pairAnswer.useQuizContext());
    }

    @Test
    void pairingCanHideOnlyPromptInstanceLinksWithoutChangingContentOrAnswers() throws Exception {
        var passage = new Passage(item);
        ViewConfig selection = ViewConfig.leaf();
        selection.addField("passage", ViewConfig.leaf());
        ViewConfig hidden = selection.copy().instanceTextLinksVisible(false);
        var extractor = new ViewableKeyExtractor();
        var paths = extractor.paths(passage, selection);
        var key = extractor.combinations(passage, paths).getFirst();
        assertEquals(List.of(passage.passage.text()), key);
        assertEquals(paths, extractor.paths(passage, hidden));
        assertEquals(key, extractor.combinations(passage, hidden).getFirst());

        var prompt = extractor.contentObject(passage, hidden, paths, key);
        var answer = extractor.contentObject(passage, selection, paths, key);
        SwingUtilities.invokeAndWait(() -> {
            var promptCard = factory.create(
                    prompt.object(), prompt.viewConfig(), QuizCardRole.PAIR_PROMPT);
            var answerCard = factory.create(
                    answer.object(), answer.viewConfig(), QuizCardRole.PAIR_ANSWER);
            TextRow promptRow = passageRow(promptCard);
            TextRow answerRow = passageRow(answerCard);
            assertNotNull(promptRow);
            assertNotNull(answerRow);
            assertTrue(promptRow.matchesRenderedText(List.of(passage.passage.text()), true));
            assertEquals(passage.passage.urls(), promptRow.linkedUrls());
            assertFalse(hasTooltip(promptRow, "Item"));
            assertTrue(hasTooltip(answerRow, "Item"));
            assertSame(passage.passage, FieldSet.of(prompt.object()).read("passage"));
            assertSame(item, passage.passage.linkedPartAt(0).reference());
        });
    }

    private static TextRow passageRow(Component component) {
        if (component instanceof TextRow row && row.represents(FieldPath.of("passage"))) return row;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                TextRow row = passageRow(child);
                if (row != null) return row;
            }
        }
        return null;
    }

    private static boolean hasTooltip(TextRow row, String destination) {
        row.setSize(360, 40);
        for (int y = 0; y < row.getHeight(); y++) {
            for (int x = 0; x < row.getWidth(); x++) {
                if (destination.equals(row.getToolTipText(new MouseEvent(row,
                        MouseEvent.MOUSE_MOVED, 0, 0, x, y, 0, false)))) return true;
            }
        }
        return false;
    }

    private static final class Passage extends ViewableAdapter {
        private final LinkedTextSequence passage;

        private Passage(Item character) {
            passage = LinkedTextSequence.of(
                    LinkedTextSequence.reference("Item", character),
                    LinkedTextSequence.text(" in "),
                    LinkedTextSequence.link("the source", "https://example.test/passage"));
        }

        @Override public String getIdentifier() { return "passage"; }
        @Override public String getDisplayName() { return "Passage"; }
    }

    private static ViewConfig configWithNestedField() {
        ViewConfig config = ViewConfig.of(Item.class);
        config.setAllFields(false);
        config.addField("detail", ViewConfig.leaf());
        return config;
    }

    private static final class Item extends ViewableAdapter {
        @SuppressWarnings("unused")
        private final String detail = "detail";

        @Override public String getIdentifier() { return "item"; }
        @Override public String getDisplayName() { return "Item"; }
    }
}
