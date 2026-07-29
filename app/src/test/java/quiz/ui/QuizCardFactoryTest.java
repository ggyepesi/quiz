package quiz.ui;

import objectview.viewconfig.ViewConfig;
import org.junit.jupiter.api.Test;
import objectview.ViewableAdapter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void pairingPreservesEditorMediaChoicesAndSuppliesMissingRootClass() {
        ViewConfig source = configWithNestedField();
        source.setCls(null);
        source.setThumb(true);
        source.getFieldConfig("detail").setThumb(false);

        ViewConfig result = factory.configurationFor(
                item, source,
                QuizCardPresentation.forRole(QuizCardRole.PAIR_ANSWER));

        assertEquals(Item.class, result.getCls());
        assertTrue(result.isThumb());
        assertFalse(result.getFieldConfig("detail").isThumb());
        assertNull(source.getCls());
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
