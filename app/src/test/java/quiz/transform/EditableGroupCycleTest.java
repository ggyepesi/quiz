package quiz.transform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The group-tree walkers must terminate on a back-edge, not StackOverflow. */
class EditableGroupCycleTest {

    @Test @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void reproduceAndCopyTerminateOnACyclicGraph() {
        EditableGroup a = new EditableGroup("A");
        EditableGroup b = new EditableGroup("B");
        a.addGroup(b);
        b.addGroup(a);   // A <-> B cycle

        a.reproduceDescendants();   // must not recurse forever

        EditableGroup copy = EditableGroup.copyOf(a);
        assertNotNull(copy);
        // The back-edge reconstructs to the SAME copy, not a fresh infinite chain.
        EditableGroup copyB = (EditableGroup) copy.getChild("B");
        assertNotNull(copyB);
        assertSame(copy, copyB.getChild("A"));
    }
}
