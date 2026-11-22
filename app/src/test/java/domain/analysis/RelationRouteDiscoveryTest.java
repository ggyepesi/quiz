package domain.analysis;

import domain.datasource.graph.GraphTraversalDirection;
import domain.datasource.graph.RelationRoute;
import domain.datasource.wikidata.WikidataRouteRelation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The Laocoön case is the reason route discovery exists: a direct child statement finds
 * one son, while a qualified group entity followed by has-part finds both. Discovery
 * must return the exact typed route that generation can execute and rank it by evidence
 * coverage, not turn those three properties into a special mythology rule.
 */
class RelationRouteDiscoveryTest {

    @Test
    void aQualifiedGroupRouteIsRankedAboveTheIncompleteDirectProperty() throws Exception {
        Map<String, List<RelationRouteDiscovery.Segment>> graph = new LinkedHashMap<>();
        graph.put("Q22878", List.of(
                segment("Q12748409", property("P40"), "child (P40)"),
                segment("Q12748409", property("P999"), "broad relation (P999)"),
                segment("Q3991395", property("P999"), "broad relation (P999)"),
                segment("Q5", property("P999"), "broad relation (P999)"),
                segment("Q37737651",
                        statement("P40"), "statement child (P40)",
                        qualifier("P3831"), "qualifier object has role (P3831)")));
        graph.put("Q37737651", List.of(
                segment("Q12748409", property("P527"), "has part (P527)"),
                segment("Q3991395", property("P527"), "has part (P527)")));

        RelationRouteDiscovery.Result result = RelationRouteDiscovery.discover(
                List.of(new RelationRouteDiscovery.Example("Q22878",
                        List.of("Q12748409", "Q3991395"))),
                3, 20, qids -> new RelationRouteDiscovery.ReadResult(
                        qids.stream().collect(java.util.stream.Collectors.toMap(
                                qid -> qid, qid -> graph.getOrDefault(qid, List.of()),
                                (first, ignored) -> first, LinkedHashMap::new)), false));

        assertFalse(result.discoveryLimitReached());
        assertEquals(3, result.candidates().size());
        RelationRouteDiscovery.Candidate complete = result.candidates().getFirst();
        assertEquals(2, complete.matchedExamples());
        assertEquals(0, complete.additionalResults());
        assertEquals(List.of("statement:P40", "qualifier:P3831", "P527"),
                complete.route().steps().stream()
                        .map(step -> step.relation().relationId()).toList());
        assertEquals(List.of(GraphTraversalDirection.OUTGOING,
                        GraphTraversalDirection.OUTGOING,
                        GraphTraversalDirection.OUTGOING),
                complete.route().steps().stream().map(RelationRoute.Step::direction).toList());
        RelationRouteDiscovery.Candidate broad = result.candidates().get(1);
        assertEquals(2, broad.matchedExamples());
        assertEquals(1, broad.additionalResults(),
                "a route that reaches every expected example plus Q5 must expose "
                        + "that breadth before the user adds it");
        assertEquals(1, result.candidates().get(2).matchedExamples());
    }

    private static RelationRouteDiscovery.Segment segment(
            String target, Object... stepAndLabel) {
        java.util.ArrayList<RelationRoute.Step> steps = new java.util.ArrayList<>();
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        for (int i = 0; i < stepAndLabel.length; i += 2) {
            steps.add((RelationRoute.Step) stepAndLabel[i]);
            labels.add((String) stepAndLabel[i + 1]);
        }
        return new RelationRouteDiscovery.Segment(steps, labels, target, target);
    }

    private static RelationRoute.Step property(String pid) {
        return step(WikidataRouteRelation.Kind.PROPERTY, pid);
    }
    private static RelationRoute.Step statement(String pid) {
        return step(WikidataRouteRelation.Kind.STATEMENT, pid);
    }
    private static RelationRoute.Step qualifier(String pid) {
        return step(WikidataRouteRelation.Kind.QUALIFIER, pid);
    }
    private static RelationRoute.Step step(WikidataRouteRelation.Kind kind, String pid) {
        return new RelationRoute.Step(new WikidataRouteRelation(kind, pid).relation(),
                GraphTraversalDirection.OUTGOING);
    }
}
