package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.api.FakeWikidataApiClient;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.CanonicalSpec;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisambiguationPruneTest {

    /** Model: Nomination.ceremony -> Ceremony, Nominee.type (P31) -> a vocab. */
    private static GeneratedProjectModel model() {
        GeneratedProjectModel m = new GeneratedProjectModel();
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.addField("ceremony", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Ceremony");
        nom.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Nominee");
        m.addClass(nom);
        m.addClass(new GeneratedClassModel("Ceremony"));   // no P31 field -> fetched
        GeneratedClassModel nominee = new GeneratedClassModel("Nominee");
        nominee.addField("type", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .mapping().propertyPid("P31");             // P31 read off the instance
        m.addClass(nominee);
        m.rootClass(nom);
        return m;
    }

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

        List<WikidataDynamicObject> pool =
                new java.util.ArrayList<>(List.of(badNom, goodNom));

        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .entity("Q1209673", "1968 Academy Awards",
                        Map.of("P31", List.of("Q4167410")))     // disambiguation page
                .entity("Q100", "39th Academy Awards",
                        Map.of("P31", List.of("Q4504495")));    // award ceremony

        Set<WikidataDynamicObject> removed =
                DisambiguationPrune.apply(model(), pool, api, null);
        pool.removeIf(removed::contains);

        assertTrue(pool.contains(badNom), "the nomination survives");
        assertNull(badNom.get("ceremony"), "the disambiguation link is scrubbed");
        assertEquals(realCeremony, goodNom.get("ceremony"));
        assertFalse(pool.contains(disambig));
    }

    /** A stamped member that already carries its P31 (Nominee.type) is vetted WITHOUT
     *  a fetch: a nominee whose loaded P31 is a disambiguation page is pruned. */
    @Test void detectsViaTheAlreadyLoadedP31FieldWithoutFetching() {
        WikidataDynamicObject badNominee =
                new WikidataDynamicObject("Q999", "Some disambiguation");
        badNominee.type("Nominee");
        badNominee.put("type", List.of(
                new WikidataDynamicObject("Q4167410", "Wikimedia disambiguation page")));
        WikidataDynamicObject nom = new WikidataDynamicObject("Q9$s1", "n");
        nom.type("Nomination");
        nom.put("nominee", badNominee);

        List<WikidataDynamicObject> pool = new java.util.ArrayList<>(List.of(nom));

        // No entity() stubs: if it tried to fetch, it would find nothing — proving the
        // detection came from the instance's own P31 field.
        FakeWikidataApiClient api = new FakeWikidataApiClient();

        Set<WikidataDynamicObject> removed =
                DisambiguationPrune.apply(model(), pool, api, null);

        assertTrue(removed.contains(badNominee));
        assertNull(nom.get("nominee"), "the bad nominee link is scrubbed");
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
                .entity("Q100", "39th Academy Awards", Map.of("P31", List.of("Q4504495")));

        assertTrue(DisambiguationPrune.apply(model(), pool, api, null).isEmpty());
        assertEquals(c, nom.get("ceremony"));
    }

    @Test void aMixedPurposeItemIsNotPruned() {
        WikidataDynamicObject position =
                new WikidataDynamicObject("Q693614", "King of Jerusalem");
        position.type("Ceremony");
        WikidataDynamicObject record = new WikidataDynamicObject("Q1$s1", "holding");
        record.type("Nomination");
        record.put("ceremony", position);

        FakeWikidataApiClient api = new FakeWikidataApiClient().entity(
                "Q693614", "King of Jerusalem", Map.of("P31", List.of(
                        "Q13406463", "Q355567", "Q114962596")));

        assertTrue(DisambiguationPrune.apply(model(), List.of(record), api, null).isEmpty());
        assertEquals(position, record.get("ceremony"));
    }

    @Test void mixedLoadedP31AlsoKeepsTheEntityWithoutFetching() {
        WikidataDynamicObject nominee =
                new WikidataDynamicObject("Q693614", "King of Jerusalem");
        nominee.type("Nominee");
        nominee.put("type", List.of(
                new WikidataDynamicObject("Q13406463", "Wikimedia list article"),
                new WikidataDynamicObject("Q355567", "noble title")));
        WikidataDynamicObject record = new WikidataDynamicObject("Q1$s1", "record");
        record.type("Nomination");
        record.put("nominee", nominee);

        assertTrue(DisambiguationPrune.apply(
                model(), List.of(record), new FakeWikidataApiClient(), null).isEmpty());
        assertEquals(nominee, record.get("nominee"));
    }

    @Test void scrubbingAReferenceResettlesADerivedName() {
        GeneratedProjectModel model = model();
        model.findClass("Nomination").canonical()
                .displayNameMode(CanonicalSpec.DisplayNameMode.TEMPLATE)
                .displayNameTemplate("{nominee} — {ceremony}");
        WikidataDynamicObject bad = new WikidataDynamicObject("Q999", "Bad ceremony");
        bad.type("Ceremony");
        WikidataDynamicObject nominee = new WikidataDynamicObject("Q5", "Someone");
        nominee.type("Nominee");
        WikidataDynamicObject record = new WikidataDynamicObject(
                "Q1$s1", "Someone — Bad ceremony");
        record.type("Nomination");
        record.put("nominee", nominee);
        record.put("ceremony", bad);
        FakeWikidataApiClient api = new FakeWikidataApiClient().entity(
                "Q999", "Bad ceremony", Map.of("P31", List.of("Q4167410")));

        DisambiguationPrune.apply(model, List.of(record), api, null);

        assertNull(record.get("ceremony"));
        assertEquals("Someone —", record.getDisplayName());
    }
}
