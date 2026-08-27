package wikidata.explore.query.logical;

import datasource.graph.GraphExpansionPattern;
import datasource.graph.GraphRelation;
import org.junit.jupiter.api.Test;
import wikidata.FakeWikidataSparqlClient;
import wikidata.explore.query.core.WikidataAccess;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphPatternSampleQueryTest {
    @Test void previewsTheReverseSubjectAndAllOfItsStatementValues() throws Exception {
        GraphExpansionPattern pattern = new GraphExpansionPattern(
                "OfficeHolding:P39:Position", "Person", "Position",
                new GraphRelation("wikidata", "P39"), "OfficeHolding",
                "source", "position");
        try (FakeWikidataSparqlClient sparql = new FakeWikidataSparqlClient()
                .row(Map.of(
                        "expanded", "http://www.wikidata.org/entity/Q6412254",
                        "expandedLabel", "Apostolic King of Hungary",
                        "source", "http://www.wikidata.org/entity/Q82686",
                        "sourceLabel", "Béla II of Hungary",
                        "statement", "http://www.wikidata.org/entity/statement/Q82686-abc",
                        "target", "http://www.wikidata.org/entity/Q253779",
                        "targetLabel", "Ban of Croatia"))) {
            var result = new GraphPatternSampleQuery(
                    pattern, List.of("Q6412254"), 20)
                    .execute(WikidataAccess.of(sparql, null).bind());

            assertEquals("Q6412254", result.selected().getFirst().qid());
            assertEquals("Q82686", result.sources().getFirst().qid());
            assertEquals("Q253779", result.targets().getFirst().qid());
            assertEquals("Q82686-abc", result.statements().getFirst().statementId());
        }
    }
}
