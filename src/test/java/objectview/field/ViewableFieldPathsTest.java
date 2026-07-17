package objectview.field;
import objectview.ViewableAdapter;

import objectview.annotations.NotViewableField;
import objectview.viewconfig.ViewablePanelConfig;
import org.junit.jupiter.api.Test;
import objectview.ImagePane;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ViewableFieldPathsTest {

    @Test
    void collectionOfStringIsLeafWhenSelected() {
        ViewablePanelConfig config = ViewablePanelConfig.of(TestCard.class);
        config.setAllFields(false);
        config.addField("tags", ViewablePanelConfig.leaf());

        List<ViewableFieldPaths.FieldPath> paths =
                ViewableFieldPaths.collect(config, ViewableFieldPaths.NOT_IMAGE_PANE_FIELDS);

        assertEquals(Set.of("tags"), pathStrings(paths));
    }

    @Test
    void collectionOfQuizableUsesNestedSelectedFieldsOnly() {
        ViewablePanelConfig childConfig = ViewablePanelConfig.of(TestChild.class);
        childConfig.setAllFields(false);
        childConfig.addField("name", ViewablePanelConfig.leaf());

        ViewablePanelConfig config = ViewablePanelConfig.of(TestCard.class);
        config.setAllFields(false);
        config.addField("children", childConfig);

        List<ViewableFieldPaths.FieldPath> paths =
                ViewableFieldPaths.collect(config, ViewableFieldPaths.NOT_IMAGE_PANE_FIELDS);

        assertEquals(Set.of("children.name"), pathStrings(paths));
    }

    @Test
    void imagePaneFieldsAreExcluded() {
        ViewablePanelConfig config = ViewablePanelConfig.of(TestCard.class);
        config.setAllFields(false);
        config.addField("name", ViewablePanelConfig.leaf());
        config.addField("image", ViewablePanelConfig.leaf());

        List<ViewableFieldPaths.FieldPath> paths =
                ViewableFieldPaths.collect(config, ViewableFieldPaths.NOT_IMAGE_PANE_FIELDS);

        assertEquals(Set.of("name"), pathStrings(paths));
    }

    @Test
    void recursiveTypeDoesNotOverflowWhenOnlyNameIsSelected() {
        ViewablePanelConfig config = ViewablePanelConfig.of(SelfNode.class);
        config.setAllFields(false);
        config.addField("name", ViewablePanelConfig.leaf());

        List<ViewableFieldPaths.FieldPath> paths =
                ViewableFieldPaths.collect(config, ViewableFieldPaths.NOT_IMAGE_PANE_FIELDS);

        assertEquals(Set.of("name"), pathStrings(paths));
    }

    private Set<String> pathStrings(List<ViewableFieldPaths.FieldPath> paths) {
        return paths.stream()
                .map(p -> String.join(".", p.path()))
                .collect(Collectors.toSet());
    }

    @SuppressWarnings("unused")
    private static class TestCard extends ViewableAdapter {
        private String name;
        private List<String> tags;
        private List<TestChild> children;
        private ImagePane image;

        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
    }

    @SuppressWarnings("unused")
    private static class TestChild extends ViewableAdapter {
        private String name;
        private String code;

        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
    }

    @SuppressWarnings("unused")
    private static class SelfNode extends ViewableAdapter {
        private String name;
        private List<SelfNode> children;

        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
    }

    // Mirrors WikidataDynamicObject: identity fields hidden from the card, and a
    // bare reference has no other fields.
    @SuppressWarnings("unused")
    private static class EntityCard extends ViewableAdapter {
        @NotViewableField
        private String qid;
        @NotViewableField
        private String name;

        @Override public String getIdentifier() { return qid; }
        @Override public String getDisplayName() { return name; }
    }

    @Test
    void allFieldsImpliesIdentityExplicitConfigDoesNot() {
        // "All fields" implies the identity (name/qid). An EXPLICIT config means
        // exactly what it names — forcing name in regardless made search hit on
        // name even when the user unchecked it.
        ViewablePanelConfig all = ViewablePanelConfig.of(EntityCard.class);
        all.setAllFields(true);
        Set<String> allPaths = pathStrings(ViewableFieldPaths.collect(
                all, ViewableFieldPaths.NOT_IMAGE_PANE_FIELDS));
        assertTrue(allPaths.contains("name"), allPaths.toString());
        assertTrue(allPaths.contains("qid"), allPaths.toString());

        ViewablePanelConfig explicit = ViewablePanelConfig.of(EntityCard.class);
        explicit.setAllFields(false);
        Set<String> explicitPaths = pathStrings(ViewableFieldPaths.collect(
                explicit, ViewableFieldPaths.NOT_IMAGE_PANE_FIELDS));
        assertFalse(explicitPaths.contains("name"), explicitPaths.toString());
        assertFalse(explicitPaths.contains("qid"), explicitPaths.toString());
    }

    @Test
    void dedupByPathKeepsFirstOfEachDistinctPath() {
        ViewableFieldPaths.FieldPath name =
                new ViewableFieldPaths.FieldPath("name", List.of("name"), null);
        ViewableFieldPaths.FieldPath nameAgain =
                new ViewableFieldPaths.FieldPath("name (dup)", List.of("name"), null);
        ViewableFieldPaths.FieldPath code =
                new ViewableFieldPaths.FieldPath("code", List.of("code"), null);

        List<ViewableFieldPaths.FieldPath> out =
                ViewableFieldPaths.dedupByPath(List.of(name, nameAgain, code));

        assertEquals(2, out.size());
        assertSame(name, out.get(0), "first occurrence of the duplicated path is kept");
        assertEquals(List.of("code"), out.get(1).path());
    }

    @Test
    void collectSurfacesIdentityExactlyOnce() {
        // Identity (name + qid) must appear once each — never doubled — so a
        // duplicated field can't build an inconsistent composite sort/search key.
        ViewablePanelConfig config = ViewablePanelConfig.of(EntityCard.class);

        List<ViewableFieldPaths.FieldPath> paths = ViewableFieldPaths.collect(
                config, ViewableFieldPaths.NOT_IMAGE_PANE_FIELDS);

        List<List<String>> allPaths = paths.stream()
                .map(ViewableFieldPaths.FieldPath::path)
                .collect(Collectors.toList());

        assertEquals(allPaths.stream().distinct().count(), allPaths.size(),
                "no duplicate paths: " + allPaths);
        assertEquals(1, allPaths.stream().filter(p -> p.equals(List.of("name"))).count());
        assertEquals(1, allPaths.stream().filter(p -> p.equals(List.of("qid"))).count());
    }

    @Test
    void nonEntityDoesNotGetSyntheticQid() {
        // No qid field → identity-field injection is a no-op (existing behavior).
        ViewablePanelConfig config = ViewablePanelConfig.of(TestChild.class);
        config.setAllFields(false);

        Set<String> paths = pathStrings(ViewableFieldPaths.collect(
                config, ViewableFieldPaths.NOT_IMAGE_PANE_FIELDS));

        assertFalse(paths.contains("qid"), paths.toString());
    }
}