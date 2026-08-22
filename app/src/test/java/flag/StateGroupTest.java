package flag;

import org.junit.jupiter.api.Test;
import quiz.group.ViewableGroup;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StateGroupTest {

    @Test void groupReferenceLabelIncludesItsFullAncestry() {
        ViewableGroup vienna = new ViewableGroup("All")
                .getOrCreateChild("Capitals")
                .getOrCreateChild("VI")
                .getOrCreateChild("Vienna");

        assertEquals("All/Capitals/VI/Vienna",
                vienna.getReferenceLabel());
        assertEquals("Vienna", vienna.getDisplayName(),
                "the group's own local title must remain unchanged");
    }

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
