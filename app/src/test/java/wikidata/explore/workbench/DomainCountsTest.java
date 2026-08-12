package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The counts row is a drift log, so it has to count what the snapshot holds. The version
 * this replaces counted top-level objects by {@code qid()}, which silently dropped every
 * reified record (a statement id is not a QID) and every referent that lives only nested
 * inside one — the two populations most likely to drift.
 */
class DomainCountsTest {

    private static WikidataDynamicObject object(String id, String name, String type) {
        WikidataDynamicObject o = new WikidataDynamicObject(id, name);
        o.type(type);
        return o;
    }

    @Test void aReifiedRecordIsCountedDespiteItsStatementId() {
        WikidataDynamicObject nomination = object(
                "Q72717$67ADCA97-2FF9-43AD-A4DC-0349086680AC", "Elia Kazan", "Nomination");

        String row = DomainCounts.row(List.of(nomination));

        assertTrue(row.contains("Nomination=1"), row);
        assertTrue(row.startsWith("total=1"), row);
    }

    @Test void aReferentReachableOnlyInsideARecordIsCounted() {
        WikidataDynamicObject ceremony = object("Q303473", "20th Academy Awards", "Ceremony");
        WikidataDynamicObject nomination = object("Q1$stmt", "A nomination", "Nomination");
        nomination.put("ceremony", ceremony);

        String row = DomainCounts.row(List.of(nomination));   // ceremony is NOT a root

        assertTrue(row.contains("Ceremony=1"), row);
        assertTrue(row.startsWith("total=2"), row);
    }

    /** Roles overlap: one entity is both, so the columns exceed the entity total. */
    @Test void anEntityInTwoRolesCountsInBothWithoutInflatingTheTotal() {
        WikidataDynamicObject shared = object("Q204191", "It's a Wonderful Life", "ForWork");
        shared.assignClass("Nominee");
        WikidataDynamicObject nomination = object("Q1$stmt", "A nomination", "Nomination");
        nomination.put("nominee", shared);
        nomination.put("forWork", shared);

        String row = DomainCounts.row(List.of(nomination));

        assertTrue(row.contains("Nominee=1"), row);
        assertTrue(row.contains("ForWork=1"), row);
        assertTrue(row.startsWith("total=2"), row);
    }

    @Test void bareReferencesAndInternalLoadTypesAreNotClasses() {
        WikidataDynamicObject genre = new WikidataDynamicObject("Q471839", "science fiction film");
        WikidataDynamicObject plumbing = object("Q5", "human", "__subject_Nomination");
        WikidataDynamicObject nomination = object("Q1$stmt", "A nomination", "Nomination");
        nomination.put("genre", genre);
        nomination.put("host", plumbing);

        String row = DomainCounts.row(List.of(nomination));

        assertFalse(row.contains("__subject_"), row);
        assertFalse(row.contains("WikidataDynamicObject"), row);
        assertEquals("total=2\tNomination=1", row, "only the two typed, non-internal classes");
    }
}
