package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrphanPruneTest {

    @Test void prunesOnlyUntypedUnreferencedNodes() {
        WikidataDynamicObject nom = new WikidataDynamicObject("Q1", "a nomination");
        nom.type("Nomination");

        WikidataDynamicObject nominee = new WikidataDynamicObject("Q42", "Meryl Streep");
        nominee.type("Nominee");
        nom.put("nominee", nominee);

        // Untyped BUT referenced (a co-nominee held in a list) -> kept.
        WikidataDynamicObject refdUntyped = new WikidataDynamicObject("Q7", "co-nominee");
        nom.put("coNominees", List.of(refdUntyped));

        // Untyped AND referenced by nothing -> the orphan.
        WikidataDynamicObject orphan = new WikidataDynamicObject("Q99", "Mel Brooks");

        Set<WikidataDynamicObject> orphans = OrphanPrune.apply(
                List.of(nom, nominee, refdUntyped, orphan), null);

        assertEquals(1, orphans.size());
        assertTrue(orphans.contains(orphan));
        assertFalse(orphans.contains(nom));          // typed root
        assertFalse(orphans.contains(nominee));      // typed
        assertFalse(orphans.contains(refdUntyped));  // untyped but referenced
    }
}
