package quiz;

import org.junit.jupiter.api.Test;
import quiz.ui.ImagePane;
import quiz.ui.viewconfig.QuizablePanelConfig;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class QuizableFieldPathsTest {

    @Test
    void collectionOfStringIsLeafWhenSelected() {
        QuizablePanelConfig config = QuizablePanelConfig.of(TestCard.class);
        config.setAllFields(false);
        config.addField("tags", QuizablePanelConfig.leaf());

        List<QuizableFieldPaths.FieldPath> paths =
                QuizableFieldPaths.collect(config, QuizableFieldPaths.NOT_IMAGE_PANE_FIELDS);

        assertEquals(Set.of("tags"), pathStrings(paths));
    }

    @Test
    void collectionOfQuizableUsesNestedSelectedFieldsOnly() {
        QuizablePanelConfig childConfig = QuizablePanelConfig.of(TestChild.class);
        childConfig.setAllFields(false);
        childConfig.addField("name", QuizablePanelConfig.leaf());

        QuizablePanelConfig config = QuizablePanelConfig.of(TestCard.class);
        config.setAllFields(false);
        config.addField("children", childConfig);

        List<QuizableFieldPaths.FieldPath> paths =
                QuizableFieldPaths.collect(config, QuizableFieldPaths.NOT_IMAGE_PANE_FIELDS);

        assertEquals(Set.of("children.name"), pathStrings(paths));
    }

    @Test
    void imagePaneFieldsAreExcluded() {
        QuizablePanelConfig config = QuizablePanelConfig.of(TestCard.class);
        config.setAllFields(false);
        config.addField("name", QuizablePanelConfig.leaf());
        config.addField("image", QuizablePanelConfig.leaf());

        List<QuizableFieldPaths.FieldPath> paths =
                QuizableFieldPaths.collect(config, QuizableFieldPaths.NOT_IMAGE_PANE_FIELDS);

        assertEquals(Set.of("name"), pathStrings(paths));
    }

    @Test
    void recursiveTypeDoesNotOverflowWhenOnlyNameIsSelected() {
        QuizablePanelConfig config = QuizablePanelConfig.of(SelfNode.class);
        config.setAllFields(false);
        config.addField("name", QuizablePanelConfig.leaf());

        List<QuizableFieldPaths.FieldPath> paths =
                QuizableFieldPaths.collect(config, QuizableFieldPaths.NOT_IMAGE_PANE_FIELDS);

        assertEquals(Set.of("name"), pathStrings(paths));
    }

    private Set<String> pathStrings(List<QuizableFieldPaths.FieldPath> paths) {
        return paths.stream()
                .map(p -> String.join(".", p.path()))
                .collect(Collectors.toSet());
    }

    @SuppressWarnings("unused")
    private static class TestCard extends QuizableAdapter {
        private String name;
        private List<String> tags;
        private List<TestChild> children;
        private ImagePane image;

        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
    }

    @SuppressWarnings("unused")
    private static class TestChild extends QuizableAdapter {
        private String name;
        private String code;

        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
    }

    @SuppressWarnings("unused")
    private static class SelfNode extends QuizableAdapter {
        private String name;
        private List<SelfNode> children;

        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
    }
}