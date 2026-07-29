package quiz;

import objectview.ViewableAdapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class ViewableGroupTest {

    @Test void acceptsAViewableThatIsNotViewable() {
        ViewableAdapter viewable = new ViewableAdapter() {
            @Override public String getIdentifier() {
                return "view-only";
            }

            @Override public String getDisplayName() {
                return "View only";
            }
        };
        ViewableGroup group = new ViewableGroup("All");

        group.addMember(viewable);

        assertSame(viewable, group.getMembers().iterator().next());
    }
}
