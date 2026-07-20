package wikidata.explore.extract;

import org.junit.jupiter.api.Test;
import wikidata.FakeWikidataSparqlClient;
import wikidata.api.FakeWikidataApiClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueVocabularyDiscoveryTest {

    @Test void discoversDistinctLabelledValuesOfAProperty() {
        // Two nominees; their P31 values (with a duplicate) come back from SPARQL.
        FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient()
                .row(Map.of("value", "Q5"))          // human
                .row(Map.of("value", "Q95074"))      // fictional character
                .row(Map.of("value", "Q5"));         // dup -> collapsed

        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .entity("Q5", "human")
                .entity("Q95074", "fictional character");

        List<WikidataDynamicObject> vocab = new ValueVocabularyDiscovery().discover(
                List.of("Q42", "Q43"), "P31", 50, 200, sparql, api, null);

        assertEquals(2, vocab.size(), "distinct values only");
        assertEquals("human", vocab.get(0).getDisplayName());
        assertEquals("Q5", vocab.get(0).qid());
        assertEquals("fictional character", vocab.get(1).getDisplayName());
    }

    @Test void guardsAgainstNoPropertyOrNoSubjects() {
        FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient().row(Map.of("value", "Q5"));
        FakeWikidataApiClient api = new FakeWikidataApiClient().entity("Q5", "human");

        // No valid property -> no query, empty.
        assertTrue(new ValueVocabularyDiscovery()
                .discover(List.of("Q42"), "not-a-pid", 50, 200, sparql, api, null).isEmpty());
        // No subjects -> no query, empty (never an unbounded scan).
        assertTrue(new ValueVocabularyDiscovery()
                .discover(List.of(), "P31", 50, 200, sparql, api, null).isEmpty());
    }
}
