package quiz.enrichment.ui;

import datasource.enrichment.SourceYield;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceYieldCardsTest {

    /** "DBpedia was asked and had nothing" is the answer to why a member shows no
     *  value. A source never reached answers nothing, so it says nothing. */
    @Test void aSourceAskedReportsEvenWhenItFoundNothing() {
        List<objectview.Viewable> cards = SourceYieldCards.of(List.of(
                measured("Wikidata", 1, 0),
                measured("DBpedia", 0, 1)));

        assertEquals(1, cards.size(), "only the source that was actually asked");
        assertEquals("Wikidata (0)", cards.getFirst().getDisplayName(),
                "asked, and it had none");
    }

    @Test void aggregatingKeepsTheCandidatesEachSourceContributed() {
        SourceYield first = measured("Wikidata", 1, 0);
        SourceYield merged = first.plus(measured("Wikidata", 2, 0));

        assertEquals(3, merged.examined());
        assertEquals(0, merged.usableChanges(), "no candidates were supplied here");
        assertEquals(3, merged.completed(), "examined minus failed, not a stored third");
    }

    /** Counts come from a counter that only increments, so an impossible one is a defect
     *  in whatever measured — reporting it as plausible zero work would hide that. */
    @Test void anImpossibleCountIsRefusedRatherThanClamped() {
        assertThrows(IllegalArgumentException.class,
                () -> new SourceYield("Wikidata", -1, 0, 0, 0, List.of(), List.of(), 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SourceYield("Wikidata", 1, 2, 0, 0, List.of(), List.of(), 0, 0),
                "more failures than examinations");
        assertThrows(IllegalArgumentException.class,
                () -> new SourceYield("Wikidata", 1, 0, 0, 0, List.of(), List.of(), 0, -1));
    }

    private static SourceYield measured(String source, int examined, int skipped) {
        return new SourceYield(source, examined, 0, skipped, 0, List.of(), List.of(), 0, 5);
    }
}
