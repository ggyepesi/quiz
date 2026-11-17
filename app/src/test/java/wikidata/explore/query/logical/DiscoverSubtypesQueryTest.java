package wikidata.explore.query.logical;

import org.junit.jupiter.api.Test;
import wikidata.FakeWikidataSparqlClient;
import wikidata.explore.query.core.WikidataAccess;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiscoverSubtypesQueryTest {
    @Test void subtypeCountAndRepresentativeMemberBecomeTheSharedCandidate()
            throws Exception {
        try (FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient().row(Map.of(
                "type", "http://www.wikidata.org/entity/Q63143903",
                "typeLabel", "episode in Greek mythology",
                "nMembers", "60",
                "example", "http://www.wikidata.org/entity/Q3088950",
                "examples", "Cleaning the Augean stables"))) {
            var result = new DiscoverSubtypesQuery("Q24336466", 200)
                    .execute(WikidataAccess.of(sparql, null).bind());

            assertEquals(1, result.size());
            assertEquals("Q63143903", result.getFirst().qid());
            assertEquals("Q24336466", result.getFirst().anchorQid());
            assertEquals(60, result.getFirst().exactPopulation());
            assertEquals("Q3088950", result.getFirst().examples().getFirst().qid());
        }
    }
}
