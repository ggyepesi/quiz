package quiz.web;

import org.junit.jupiter.api.Test;
import quiz.ViewableGroup;
import quiz.transform.DynamicViewable;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ViewableStoreGroupMembershipTest {

    @Test void groupScopeAndCountUseOnlyExplicitMembers() throws Exception {
        DynamicViewable rootMember = member("A");
        DynamicViewable childMember = member("B");
        ViewableGroup root = new ViewableGroup("All");
        ViewableGroup child = root.getOrCreateChild("Child");
        root.addMember(rootMember, false);
        child.addMember(childMember, false);

        ViewableStore store = new ViewableStore();
        store.register(new ViewableSource() {
            @Override public String type() { return "Thing"; }
            @Override public Collection<DynamicViewable> load() {
                return List.of(rootMember, childMember);
            }
            @Override public ViewableGroup rootGroup() { return root; }
        });

        assertEquals(List.of("A"), store.members("Thing", "All").stream()
                .map(objectview.Viewable::getIdentifier).toList());
        assertEquals(List.of("B"), store.members("Thing", "All/Child").stream()
                .map(objectview.Viewable::getIdentifier).toList());
        assertEquals(1, GroupNode.of(root).count());
        assertEquals(1, GroupNode.of(root).children().get(0).count());
    }

    private static DynamicViewable member(String id) {
        DynamicViewable value = new DynamicViewable(id, id);
        value.type("Thing");
        return value;
    }
}
