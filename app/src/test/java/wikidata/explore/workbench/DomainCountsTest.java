package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The counts row is a drift log, so it has to count what the snapshot holds — which is
 * why every case here counts an actual saved file rather than the pool handed to the save.
 *
 * <p>Two versions got this wrong in the same way, from opposite ends. The first counted
 * top-level objects by {@code qid()}, dropping every reified record (a statement id is not
 * a QID) and every referent living only inside one. The second walked the pool for
 * reachability and counted objects the write does not keep: absorbing a copy of one
 * ⟨type, qid⟩ drops a base class in favour of the concrete subtype it found, so a row
 * claimed Position 357 times beside a snapshot whose every office claimed only
 * PositionWithHolders.
 */
class DomainCountsTest {

    @TempDir Path dir;

    /** The row for what a real save wrote, which is the only thing the file describes. */
    private String rowForSaved(List<WikidataDynamicObject> roots) throws Exception {
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.saveWithFieldGraph(roots, dir.resolve("counted.snapshot.json").toFile());
        return DomainCounts.row(store.persistedMembers());
    }

    private static WikidataDynamicObject object(String id, String name, String type) {
        WikidataDynamicObject o = new WikidataDynamicObject(id, name);
        o.type(type);
        return o;
    }

    @Test void aReifiedRecordIsCountedDespiteItsStatementId() throws Exception {
        WikidataDynamicObject nomination = object(
                "Q72717$67ADCA97-2FF9-43AD-A4DC-0349086680AC", "Elia Kazan", "Nomination");

        String row = rowForSaved(List.of(nomination));

        assertTrue(row.contains("Nomination=1"), row);
        assertTrue(row.startsWith("total=1"), row);
    }

    @Test void aReferentReachableOnlyInsideARecordIsCounted() throws Exception {
        WikidataDynamicObject ceremony = object("Q303473", "20th Academy Awards", "Ceremony");
        WikidataDynamicObject nomination = object("Q1$stmt", "A nomination", "Nomination");
        nomination.put("ceremony", ceremony);

        String row = rowForSaved(List.of(nomination));   // ceremony is NOT a root

        assertTrue(row.contains("Ceremony=1"), row);
        assertTrue(row.startsWith("total=2"), row);
    }

    /** Roles overlap: one entity is both, so the columns exceed the entity total. */
    @Test void anEntityInTwoRolesCountsInBothWithoutInflatingTheTotal() throws Exception {
        WikidataDynamicObject shared = object("Q204191", "It's a Wonderful Life", "ForWork");
        shared.assignClass("Nominee");
        WikidataDynamicObject nomination = object("Q1$stmt", "A nomination", "Nomination");
        nomination.put("nominee", shared);
        nomination.put("forWork", shared);

        String row = rowForSaved(List.of(nomination));

        assertTrue(row.contains("Nominee=1"), row);
        assertTrue(row.contains("ForWork=1"), row);
        assertTrue(row.startsWith("total=2"), row);
    }

    @Test void bareReferencesAndInternalLoadTypesAreNotClasses() throws Exception {
        WikidataDynamicObject genre = new WikidataDynamicObject("Q471839", "science fiction film");
        WikidataDynamicObject plumbing = object("Q5", "human", "__subject_Nomination");
        WikidataDynamicObject nomination = object("Q1$stmt", "A nomination", "Nomination");
        nomination.put("genre", genre);
        nomination.put("host", plumbing);

        String row = rowForSaved(List.of(nomination));

        assertFalse(row.contains("__subject_"), row);
        assertFalse(row.contains("WikidataDynamicObject"), row);
        assertFalse(row.contains("Position="), row);
        assertEquals("total=1\tNomination=1", row,
                "an internal load-type carrier is plumbing, not an entity the file offers");
    }

    /**
     * The property that matters, whatever the store does with copies: every class the row
     * names, and every number beside it, is a fact about the file that row accompanies.
     * The version this replaces counted a walk of the pool instead, and named Position 357
     * times beside a snapshot whose every office claimed only PositionWithHolders.
     */
    @Test void everyNumberInTheRowIsAFactAboutTheSavedFile() throws Exception {
        WikidataDynamicObject office = object("Q100268231", "an office", "PositionWithHolders");
        WikidataDynamicObject asBase = object("Q100268231", "an office", "Position");
        WikidataDynamicObject shared = object("Q204191", "a work", "ForWork");
        shared.assignClass("Nominee");
        WikidataDynamicObject holding = object("Q1$stmt", "a holding", "OfficeHolding");
        holding.put("position", office);
        holding.put("alsoPosition", asBase);
        holding.put("work", shared);

        java.io.File file = dir.resolve("agreement.snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.saveWithFieldGraph(List.of(holding), file);
        String row = DomainCounts.row(store.persistedMembers());

        var loaded = new WikidataDynamicObjectJsonStore().loadAllWithFieldGraph(file);
        java.util.Map<String, java.util.Set<String>> inFile = new java.util.HashMap<>();
        java.util.Set<String> identities = new java.util.HashSet<>();
        for (WikidataDynamicObject o : loaded.objects()) {
            if (!o.hasTypeStamp()) continue;
            identities.add(o.typeKey() + ' ' + o.getIdentifier());
            for (String c : o.directClassNames()) {
                if (!WikidataDynamicObject.isInternalClassName(c)) {
                    inFile.computeIfAbsent(c, k -> new java.util.HashSet<>())
                            .add(o.typeKey() + ' ' + o.getIdentifier());
                }
            }
        }
        for (String column : row.split("\\t")) {
            String[] parts = column.split("=");
            if (parts[0].equals("total")) {
                assertEquals(identities.size(), Integer.parseInt(parts[1]),
                        "the total counts the entities the file holds: " + row);
                continue;
            }
            assertTrue(inFile.containsKey(parts[0]),
                    "the row names " + parts[0] + ", which the file does not contain: " + row);
            assertEquals(inFile.get(parts[0]).size(), Integer.parseInt(parts[1]),
                    parts[0] + " is counted as the file holds it: " + row);
        }
        assertEquals(inFile.keySet().size(), row.split("\\t").length - 1,
                "and every class the file holds is named: " + row + " vs " + inFile.keySet());
    }
}
