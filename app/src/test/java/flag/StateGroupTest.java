package flag;

import org.junit.jupiter.api.Test;
import quiz.ViewableGroup;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StateGroupTest {

    @Test void retainsSameNamedGroupsFromDifferentBranches() {
        ViewableGroup root = new ViewableGroup("All");
        ViewableGroup territory = root.getOrCreateChild("Territories")
                .getOrCreateChild("United States");
        ViewableGroup currency = root.getOrCreateChild("Currencies")
                .getOrCreateChild("United States");
        State state = new State("Example");

        state.addGroup(territory);
        state.addGroup(currency);

        assertEquals(2, state.getGroups().size());
        assertEquals(
                java.util.Set.of(
                        "All.Territories.United States",
                        "All.Currencies.United States"),
                state.getGroups().keySet());
    }
}
