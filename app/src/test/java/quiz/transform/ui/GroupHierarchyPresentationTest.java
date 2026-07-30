package quiz.transform.ui;

import objectview.ViewableAdapter;
import objectview.group.ViewableGroup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class GroupHierarchyPresentationTest {

    private static final class Item extends ViewableAdapter {
        private final String name;
        private Item(String name) { this.name = name; }
        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
    }

    @Test void recoversTheExistingRootInsteadOfRenderingEveryNodeAsACard() {
        quiz.ViewableGroup root = new quiz.ViewableGroup("root");
        quiz.ViewableGroup node = root.getOrCreateChild("node");
        quiz.ViewableGroup leaf = node.getOrCreateChild("leaf");
        leaf.addMember(new Item("member"));

        ViewableGroup<?> presented =
                GroupHierarchyPresentation.rootOf(List.of(root), "groups");

        assertSame(root, presented);
        assertEquals(List.of(node), presented.getChildren().stream().toList());
    }

    @Test void wrapsMultipleIndependentRootsWithoutChangingTheirParents() {
        quiz.ViewableGroup first = new quiz.ViewableGroup("first");
        quiz.ViewableGroup second = new quiz.ViewableGroup("second");

        ViewableGroup<?> presented =
                GroupHierarchyPresentation.rootOf(List.of(first, second), "groups");

        assertEquals(2, presented.getChildren().size());
        assertNull(first.getParent());
        assertNull(second.getParent());
    }

    @Test void doesNotSpecialCaseMixedOrdinaryInstances() {
        assertNull(GroupHierarchyPresentation.rootOf(
                List.of(new quiz.ViewableGroup("group"), new Item("item")), "mixed"));
    }
}
