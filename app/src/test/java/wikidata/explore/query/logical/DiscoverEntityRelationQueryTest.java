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

    @Test void anAnchorWithoutIncomingEdgesStillGetsItsLabel() throws Exception {
        try (FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient().row(Map.of(
                "target", "http://www.wikidata.org/entity/Q7090792",
                "targetLabel", "Prince of Transylvania"))) {
            var result = new DiscoverEntityRelationQuery("P279", List.of("Q7090792"),
                    DiscoverEntityRelationQuery.Direction.INCOMING, 1, 20)
                    .execute(WikidataAccess.of(sparql, null).bind());

            assertEquals("Prince of Transylvania", result.nodes().getFirst().label());
            assertEquals(List.of(), result.edges());
        }
    }

    /**
     * The OPTIONAL leaves the ADJACENT variable unbound when an anchor has no relation,
     * and unbound sorts lowest in SPARQL. Ordering by the adjacent variable first would
     * therefore place every edgeless anchor ahead of every edge, and a wave truncated at
     * its row limit would keep the rows carrying no relation and discard the ones that do.
     */
    @Test void bothDirectionsOrderByTheAnchorBeforeTheNodeItReaches() {
        assertEquals("ORDER BY ?source ?target",
                DiscoverEntityRelationQuery.orderBy(
                        DiscoverEntityRelationQuery.Direction.OUTGOING));
        assertEquals("ORDER BY ?target ?source",
                DiscoverEntityRelationQuery.orderBy(
                        DiscoverEntityRelationQuery.Direction.INCOMING));
    }
}
