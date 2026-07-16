package quiz;

import objectview.annotations.NotQuizableField;
import org.junit.jupiter.api.Test;
import objectview.ImagePane;
import objectview.viewconfig.QuizablePanelConfig;

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

    // Mirrors WikidataDynamicObject: identity fields hidden from the card, and a
    // bare reference has no other fields.
    @SuppressWarnings("unused")
    private static class EntityCard extends QuizableAdapter {
        @NotQuizableField
        private String qid;
        @NotQuizableField
        private String name;

        @Override public String getIdentifier() { return qid; }
        @Override public String getDisplayName() { return name; }
    }

    @Test
    void allFieldsImpliesIdentityExplicitConfigDoesNot() {
        // "All fields" implies the identity (name/qid). An EXPLICIT config means
        // exactly what it names — forcing name in regardless made search hit on
        // name even when the user unchecked it.
        QuizablePanelConfig all = QuizablePanelConfig.of(EntityCard.class);
        all.setAllFields(true);
        Set<String> allPaths = pathStrings(QuizableFieldPaths.collect(
                all, QuizableFieldPaths.NOT_IMAGE_PANE_FIELDS));
        assertTrue(allPaths.contains("name"), allPaths.toString());
        assertTrue(allPaths.contains("qid"), allPaths.toString());

        QuizablePanelConfig explicit = QuizablePanelConfig.of(EntityCard.class);
        explicit.setAllFields(false);
        Set<String> explicitPaths = pathStrings(QuizableFieldPaths.collect(
                explicit, QuizableFieldPaths.NOT_IMAGE_PANE_FIELDS));
        assertFalse(explicitPaths.contains("name"), explicitPaths.toString());
        assertFalse(explicitPaths.contains("qid"), explicitPaths.toString());
    }

    @Test
    void dedupByPathKeepsFirstOfEachDistinctPath() {
        QuizableFieldPaths.FieldPath name =
                new QuizableFieldPaths.FieldPath("name", List.of("name"), null);
        QuizableFieldPaths.FieldPath nameAgain =
                new QuizableFieldPaths.FieldPath("name (dup)", List.of("name"), null);
        QuizableFieldPaths.FieldPath code =
                new QuizableFieldPaths.FieldPath("code", List.of("code"), null);

        List<QuizableFieldPaths.FieldPath> out =
                QuizableFieldPaths.dedupByPath(List.of(name, nameAgain, code));

        assertEquals(2, out.size());
        assertSame(name, out.get(0), "first occurrence of the duplicated path is kept");
        assertEquals(List.of("code"), out.get(1).path());
    }

    @Test
    void collectSurfacesIdentityExactlyOnce() {
        // Identity (name + qid) must appear once each — never doubled — so a
        // duplicated field can't build an inconsistent composite sort/search key.
        QuizablePanelConfig config = QuizablePanelConfig.of(EntityCard.class);

        List<QuizableFieldPaths.FieldPath> paths = QuizableFieldPaths.collect(
                config, QuizableFieldPaths.NOT_IMAGE_PANE_FIELDS);

        List<List<String>> allPaths = paths.stream()
                .map(QuizableFieldPaths.FieldPath::path)
                .collect(Collectors.toList());

        assertEquals(allPaths.stream().distinct().count(), allPaths.size(),
                "no duplicate paths: " + allPaths);
        assertEquals(1, allPaths.stream().filter(p -> p.equals(List.of("name"))).count());
        assertEquals(1, allPaths.stream().filter(p -> p.equals(List.of("qid"))).count());
    }

    @Test
    void nonEntityDoesNotGetSyntheticQid() {
        // No qid field → identity-field injection is a no-op (existing behavior).
        QuizablePanelConfig config = QuizablePanelConfig.of(TestChild.class);
        config.setAllFields(false);

        Set<String> paths = pathStrings(QuizableFieldPaths.collect(
                config, QuizableFieldPaths.NOT_IMAGE_PANE_FIELDS));

        assertFalse(paths.contains("qid"), paths.toString());
    }
}