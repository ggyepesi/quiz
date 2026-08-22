package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One decoder serves every provider, so one test covers what a discovery row means —
 *  a category, an infobox property and (later) a QID answer the same three questions. */
class SourceDiscoveryPickerTest {

    @Test void discoveryRowsBecomeSearchableViewableCards() {
        List<DiscoveredValueView> cards = SourceDiscoveryPicker.rows(List.of(
                List.of("Films set in Sierra Leone", 2, "Blood Diamond, Amistad"),
                List.of("location", 3, "Sierra Leone")));

        assertEquals(2, cards.size());
        assertEquals("Films set in Sierra Leone", cards.getFirst().getDisplayName());
        assertEquals("Films set in Sierra Leone", cards.getFirst().value());
        assertEquals(2, cards.getFirst().fields().read("have"));
        assertEquals("Sierra Leone", cards.get(1).examples());
    }

    @Test void aRowWithoutAValueIsNotACandidate() {
        assertTrue(SourceDiscoveryPicker.rows(List.of(List.of("", 1, "ignored"))).isEmpty());
    }

    /** The category editor is reached THROUGH discovery, so a discovery that cannot run
     *  must say so: silently returning turns the button that opens it into a dead control
     *  and leaves an already-configured rule with no way to be edited. */
    @Test void aDiscoveryThatCannotEvenRunTellsTheCallerInsteadOfDoingNothing() {
        AtomicBoolean dismissed = new AtomicBoolean();
        AtomicBoolean accepted = new AtomicBoolean();

        SourceDiscoveryPicker.run(null, null, null,
                new SourceDiscoveryPicker.Spec<Object>("Observed Wikipedia categories",
                        "explanation", "nothing found", "Use selected category",
                        result -> List.of()),
                choice -> accepted.set(true), () -> dismissed.set(true));

        assertTrue(dismissed.get(), "the caller must hear that no choice is coming");
        assertFalse(accepted.get());
    }

    @Test void anUncountableCoverageCellDoesNotLoseTheCandidate() {
        List<DiscoveredValueView> cards = SourceDiscoveryPicker.rows(
                List.of(List.of("location", "n/a", "Sierra Leone")));

        assertEquals(1, cards.size());
        assertEquals(0, cards.getFirst().fields().read("have"));
    }
}
