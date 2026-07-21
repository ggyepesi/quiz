package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.api.FakeWikidataApiClient;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisambiguationPruneTest {

    /** A nomination whose ceremony (a nested P805 referent) is a disambiguation page:
     *  the page is pruned and the link scrubbed, but the nomination is KEPT. */
    @Test void prunesADisambiguationReferentButKeepsTheRecord() {
        WikidataDynamicObject disambig =
                new WikidataDynamicObject("Q1209673", "1968 Academy Awards");
        disambig.type("Ceremony");
        WikidataDynamicObject realCeremony =
                new WikidataDynamicObject("Q100", "39th Academy Awards");
        realCeremony.type("Ceremony");

        WikidataDynamicObject badNom =
                new WikidataDynamicObject("Q9$s1", "A Time for Burning — Best Documentary");
        badNom.type("Nomination");
        badNom.put("ceremony", disambig);               // nested-only referent
        WikidataDynamicObject goodNom =
                new WikidataDynamicObject("Q9$s2", "Some Film — Best Picture");
        goodNom.type("Nomination");
        goodNom.put("ceremony", realCeremony);

        // Pool holds the two nominations; the disambig ceremony is only nested.
        List<WikidataDynamicObject> pool =
                new java.util.ArrayList<>(List.of(badNom, goodNom));

        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .entity("Q1209673", "1968 Academy Awards",
                        Map.of("P31", List.of("Q4167410")))     // disambiguation page
                .entity("Q100", "39th Academy Awards",
                        Map.of("P31", List.of("Q4504495")))     // award ceremony
                .entity("Q9$s1", "A Time for Burning — Best Documentary")
                .entity("Q9$s2", "Some Film — Best Picture");

        Set<WikidataDynamicObject> removed =
                DisambiguationPrune.apply(pool, api, null);
        pool.removeIf(removed::contains);

        // The nomination is kept, its bad ceremony link cleared.
        assertTrue(pool.contains(badNom), "the nomination survives");
        assertNull(badNom.get("ceremony"), "the disambiguation link is scrubbed");
        // The good nomination's real ceremony is untouched.
        assertEquals(realCeremony, goodNom.get("ceremony"));
        // The disambiguation page is not served (unreachable / removed).
        assertFalse(pool.contains(disambig));
    }

    /** No internal-type referents -> nothing pruned. */
    @Test void keepsEverythingWhenNoDisambiguation() {
        WikidataDynamicObject nom = new WikidataDynamicObject("Q9$s1", "n");
        nom.type("Nomination");
        WikidataDynamicObject c = new WikidataDynamicObject("Q100", "39th Academy Awards");
        c.type("Ceremony");
        nom.put("ceremony", c);
        List<WikidataDynamicObject> pool = new java.util.ArrayList<>(List.of(nom));

        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .entity("Q9$s1", "n")
                .entity("Q100", "39th Academy Awards", Map.of("P31", List.of("Q4504495")));

        assertTrue(DisambiguationPrune.apply(pool, api, null).isEmpty());
        assertEquals(c, nom.get("ceremony"));
    }
}
