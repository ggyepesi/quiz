package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * History moves on ARRIVAL. Both bugs this replaced came from moving it on intent,
 * so both are pinned here.
 */
class BrowseHistoryTest {

    private static BrowseHistory at(String... arrivals) {
        BrowseHistory history = new BrowseHistory();
        for (String arrival : arrivals) {
            history.goingForward();
            history.arrived(arrival);
        }
        return history;
    }

    @Test void nothingToGoBackToAtTheStart() {
        BrowseHistory history = at("Category:A");
        assertFalse(history.canGoBack());
        assertEquals("Category:A", history.current());
    }

    @Test void walkingForwardThenBackRetracesStepByStep() {
        BrowseHistory history = at("Category:A", "Category:B", "Category:C");
        assertTrue(history.canGoBack());

        assertEquals("Category:B", history.back());
        history.goingBack();
        history.arrived("Category:B");
        assertEquals("Category:B", history.current());

        assertEquals("Category:A", history.back());
        history.goingBack();
        history.arrived("Category:A");
        assertEquals("Category:A", history.current());
        assertFalse(history.canGoBack());
    }

    @Test void aFailedNavigationLeavesTheStackAndThePositionAlone() {
        BrowseHistory history = at("Category:A", "Category:B");

        history.goingForward();
        history.abandoned();          // the load failed; nothing arrived

        assertEquals("Category:B", history.current(),
                "the browser still shows B, so B is still where we are");
        assertEquals("Category:A", history.back(),
                "Back must not offer to return to the category already shown");
    }

    @Test void aFailedBackDoesNotConsumeTheEntry() {
        BrowseHistory history = at("Category:A", "Category:B");

        history.goingBack();
        history.abandoned();

        assertTrue(history.canGoBack());
        assertEquals("Category:A", history.back(), "the entry survives a failed Back");
        assertEquals("Category:B", history.current());
    }

    @Test void aTypedDestinationIsRecordedLikeAClickedOne() {
        // A -> B -> C by clicking, then D typed into the field. Back from D used to
        // skip C entirely, because only clicks were recorded.
        BrowseHistory history = at("Category:A", "Category:B", "Category:C");
        history.goingForward();
        history.arrived("Category:D");

        assertEquals("Category:C", history.back());
    }

    @Test void reloadingTheSameCategoryIsNotAStep() {
        BrowseHistory history = at("Category:A", "Category:B");
        history.goingForward();
        history.arrived("Category:B");

        assertEquals("Category:A", history.back(),
                "Back must not fill up with copies of the category already shown");
    }
}
