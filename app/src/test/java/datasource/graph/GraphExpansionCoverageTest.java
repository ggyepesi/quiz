package datasource.graph;

import datasource.EntityRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphExpansionCoverageTest {

    private static final GraphExpansionPattern PATTERN = new GraphExpansionPattern(
            "office-holding", "Person", "Position",
            new GraphRelation("knowledge-base", "holds-office"),
            "OfficeHolding", "person", "position", GraphTraversalDirection.INCOMING);

    @Test void expandedWinsAndProviderNeutralOrderIsStable() {
        EntityRef expanded = new EntityRef("catalogue-a", "position-1");
        EntityRef firstFrontier = new EntityRef("catalogue-b", "position-2");
        EntityRef secondFrontier = new EntityRef("catalogue-a", "position-3");

        List<GraphExpansionCoverage> coverage = GraphExpansionCoverage.of(
                PATTERN,
                List.of(expanded, expanded),
                List.of(firstFrontier, expanded, secondFrontier, firstFrontier));

        assertEquals(List.of(expanded, firstFrontier, secondFrontier),
                coverage.stream().map(GraphExpansionCoverage::node).toList());
        assertEquals(List.of(
                        GraphExpansionCoverage.State.EXPANDED,
                        GraphExpansionCoverage.State.ENCOUNTERED,
                        GraphExpansionCoverage.State.ENCOUNTERED),
                coverage.stream().map(GraphExpansionCoverage::state).toList());
    }

    @Test void frontierHonoursRelationAndDirectionAsPartOfTheCoverageKey() {
        EntityRef incoming = new EntityRef("catalogue", "incoming");
        EntityRef outgoing = new EntityRef("catalogue", "outgoing");
        EntityRef otherRelation = new EntityRef("catalogue", "other-relation");
        GraphRelation different = new GraphRelation("knowledge-base", "succeeds");
        GraphDiscoveryState state = new GraphDiscoveryState(List.of(PATTERN), List.of(
                item(incoming, PATTERN.relation(), GraphTraversalDirection.INCOMING),
                item(outgoing, PATTERN.relation(), GraphTraversalDirection.OUTGOING),
                item(otherRelation, different, GraphTraversalDirection.INCOMING)));

        assertEquals(List.of(incoming), state.frontier(PATTERN).stream()
                .map(GraphExpansionCoverage::node).toList());
    }

    @Test void aPatternSavedBeforeDirectionWasDeclaredDefaultsToIncoming()
            throws Exception {
        String legacy = """
                {"id":"legacy","sourceNodeClass":"Person",\
                "targetNodeClass":"Position",\
                "relation":{"providerId":"wikidata","relationId":"P39"},\
                "statementClass":"OfficeHolding","sourceField":"source",\
                "targetField":"position"}
                """;

        GraphExpansionPattern restored = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(legacy, GraphExpansionPattern.class);

        assertEquals(GraphTraversalDirection.INCOMING, restored.direction());
    }

    private static GraphExpansionCoverage item(
            EntityRef node, GraphRelation relation,
            GraphTraversalDirection direction) {
        return new GraphExpansionCoverage(PATTERN.id(), node, relation, direction,
                GraphExpansionCoverage.State.ENCOUNTERED);
    }
}
