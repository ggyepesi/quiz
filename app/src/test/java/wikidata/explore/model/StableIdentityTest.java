package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One rule renders every value that participates in a durable identity — a canonical
 * key, an aggregate key, a reify dedup key. Three copies of it existed and disagreed;
 * this pins the answers so a fourth cannot quietly appear.
 */
class StableIdentityTest {

    /** A value type whose rendering is deliberately NOT its canonical form. */
    private record Rendered(String canonical, String shown) implements aux.StableValue {
        @Override public String stableForm() { return canonical; }
        @Override public String toString() { return shown; }
    }

    @Test void aValueTypePublishingACanonicalFormIsAskedForIt() {
        assertEquals("1959-04-06",
                StableIdentity.of(new Rendered("1959-04-06", "6 April 1959")),
                "a rendering change must not silently change identity");
    }

    @Test void aDatePublishesItsOwnFormRatherThanItsRendering() {
        aux.FlexibleDate date = new aux.FlexibleDate(1956);

        assertEquals(date.stableForm(), StableIdentity.of(date));
    }

    @Test void aReferenceIsItsIdentifier() {
        wikidata.explore.extract.WikidataDynamicObject mourou =
                new wikidata.explore.extract.WikidataDynamicObject("Q556543", "Gérard Mourou");

        assertEquals("Q556543", StableIdentity.of(mourou),
                "a reference is identified, never named");
    }

    @Test void aCollectionIsASetRatherThanASequence() {
        assertEquals(StableIdentity.of(List.of("Q3", "Q1", "Q2")),
                StableIdentity.of(List.of("Q1", "Q2", "Q3")),
                "co-laureates in a different order are the same participants");
        assertEquals("[Q1,Q2,Q3]", StableIdentity.of(List.of("Q2", "Q3", "Q1")));
    }

    @Test void differentMembersStayDifferent() {
        org.junit.jupiter.api.Assertions.assertNotEquals(
                StableIdentity.of(List.of("Q1", "Q2")),
                StableIdentity.of(List.of("Q1", "Q3")));
    }

    @Test void absenceIsEmptyRatherThanTheWordNull() {
        assertEquals("", StableIdentity.of(null));
        assertEquals("[]", StableIdentity.of(List.of()));
    }
}
