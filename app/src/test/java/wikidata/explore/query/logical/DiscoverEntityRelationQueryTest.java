package wikidata.explore.query.logical;

import org.junit.jupiter.api.Test;
import wikidata.FakeWikidataSparqlClient;
import wikidata.explore.query.core.WikidataAccess;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiscoverEntityRelationQueryTest {
    @Test void followsTheSelectedPropertyOutward() throws Exception {
        try (FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient().row(Map.of(
                "source","http://www.wikidata.org/entity/Q6412254","sourceLabel","Apostolic King of Hungary",
                "target","http://www.wikidata.org/entity/Q12097","targetLabel","king"))) {
            var result = new DiscoverEntityRelationQuery("P279",List.of("Q6412254"),
                    DiscoverEntityRelationQuery.Direction.OUTGOING,1,20)
                    .execute(WikidataAccess.of(sparql,null).bind());
            assertEquals(List.of("Q6412254","Q12097"),result.nodes().stream()
                    .map(DiscoverEntityRelationQuery.Node::qid).toList());
            assertEquals(new DiscoverEntityRelationQuery.Edge("Q6412254","Q12097"),result.edges().getFirst());
        }
    }

    @Test void incomingTraversalFindsSubjectsPointingAtTheSeed() throws Exception {
        try (FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient().row(Map.of(
                "source","http://www.wikidata.org/entity/Q6412254","sourceLabel","Apostolic King of Hungary",
                "target","http://www.wikidata.org/entity/Q12097","targetLabel","king"))) {
            var result = new DiscoverEntityRelationQuery("P279",List.of("Q12097"),
                    DiscoverEntityRelationQuery.Direction.INCOMING,1,20)
                    .execute(WikidataAccess.of(sparql,null).bind());
            assertEquals(List.of("Q12097","Q6412254"),result.nodes().stream()
                    .map(DiscoverEntityRelationQuery.Node::qid).toList());
        }
    }
}
