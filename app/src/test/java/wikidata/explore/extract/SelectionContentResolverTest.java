package wikidata.explore.extract;

import org.junit.jupiter.api.Test;
import wikidata.FakeWikidataSparqlClient;
import wikidata.api.FakeWikidataApiClient;
import wikidata.explore.model.PopulationSelection;
import wikidata.explore.model.VocabularySelection;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice 2: a Selection's content is inspectable — a VOCABULARY resolves to its
 * members as labelled objects, in declared order, ready to render.
 */
class SelectionContentResolverTest {

    @Test void vocabularyResolvesToLabelledMembersInOrder() {
        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .entity("Q102427", "Academy Award for Best Picture")
                .entity("Q106301", "Academy Award for Best Supporting Actress");

        VocabularySelection cats = new VocabularySelection("OscarCategories");
        cats.valueQids(List.of("Q102427", "Q106301"));

        List<WikidataDynamicObject> content =
                new SelectionContentResolver().resolve(cats, api, null);

        assertEquals(2, content.size());
        assertEquals("Q102427", content.get(0).qid());
        assertEquals("Academy Award for Best Picture", content.get(0).getDisplayName());
        assertEquals("Academy Award for Best Supporting Actress",
                content.get(1).getDisplayName());
    }

    @Test void aMissingLabelFallsBackToTheQid() {
        FakeWikidataApiClient api = new FakeWikidataApiClient();   // knows nothing
        VocabularySelection s = new VocabularySelection("V");
        s.valueQids(List.of("Q999"));

        List<WikidataDynamicObject> content =
                new SelectionContentResolver().resolve(s, api, null);

        assertEquals(1, content.size());
        assertEquals("Q999", content.get(0).getDisplayName());
    }

    @Test void aTypeOnlyVocabularyResolvesToNothingYet() {
        VocabularySelection s = new VocabularySelection("ByType");
        s.valueTypeQid("Q19020");   // no explicit QIDs
        assertTrue(new SelectionContentResolver()
                .resolve(s, new FakeWikidataApiClient(), null).isEmpty());
    }

    @Test void populationResolvesItsSampledSubjectsLabelled() {
        FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient()
                .row(Map.of("subject", "Q105883400"))
                .row(Map.of("subject", "Q38195662"));
        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .entity("Q105883400", "The Whale")
                .entity("Q38195662", "Everything Everywhere All at Once");

        PopulationSelection pop = new PopulationSelection("OscarNominees");
        pop.relationPid("P1411");                 // nominated for
        pop.targetQids(List.of("Q106301"));       // into a category (bounds the scan)

        List<WikidataDynamicObject> content = new SelectionContentResolver()
                .resolve(pop, sparql, api, 200, null);

        assertEquals(2, content.size());
        assertEquals("Q105883400", content.get(0).qid());
        assertEquals("The Whale", content.get(0).getDisplayName());
        assertEquals("Everything Everywhere All at Once",
                content.get(1).getDisplayName());
    }

    @Test void populationWithoutASparqlClientResolvesToNothing() {
        PopulationSelection pop = new PopulationSelection("OscarNominees");
        pop.relationPid("P1411");
        pop.targetQids(List.of("Q106301"));
        assertTrue(new SelectionContentResolver()
                .resolve(pop, new FakeWikidataApiClient(), null).isEmpty());
    }
}
