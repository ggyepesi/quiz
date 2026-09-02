package wikidata.explore.workbench;

import datasource.EntityRef;
import datasource.graph.GraphDiscoveryState;
import datasource.graph.GraphExpansionCoverage;
import datasource.graph.GraphExpansionPattern;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.GraphTraversalStep;
import datasource.graph.GraphExpansionPolicy;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphCoverageCardDecoratorTest {

    @Test void usesExplicitMembershipAndCallsOutAnUnstampedCoveredNode() {
        GraphCoverageCardDecorator decorator = new GraphCoverageCardDecorator(state());
        WikidataDynamicObject stamped = new WikidataDynamicObject("Q2", "Position");
        stamped.type("Position");
        WikidataDynamicObject unstamped = new WikidataDynamicObject("Q2", "Position");

        assertEquals("frontier", decorator.coverageLabel(stamped));
        assertEquals("unstamped", decorator.coverageLabel(unstamped));
    }

    @Test void aFieldEdgeGetsTheSameCoverageChipAsAStatementEdge() {
        GraphRelation relation = new GraphRelation("wikidata", "P279");
        GraphTraversalStep step = new GraphTraversalStep(
                "Position.broader", "Position", "Position", "broader",
                relation, GraphTraversalDirection.OUTGOING,
                GraphExpansionPolicy.CURATED);
        GraphDiscoveryState state = new GraphDiscoveryState(List.of(), List.of(
                new GraphExpansionCoverage(step.id(), EntityRef.wikidata("Q116"),
                        relation, step.direction(),
                        GraphExpansionCoverage.State.ENCOUNTERED)));
        WikidataDynamicObject position = new WikidataDynamicObject("Q116", "monarch");
        position.type("Position");

        assertEquals("frontier",
                new GraphCoverageCardDecorator(state, List.of(step))
                        .coverageLabel(position));
    }

    private static GraphDiscoveryState state() {
        GraphRelation relation = new GraphRelation("wikidata", "P39");
        GraphExpansionPattern pattern = new GraphExpansionPattern(
                "holding:P39:position", "Person", "Position", relation,
                "OfficeHolding", "source", "position", GraphTraversalDirection.INCOMING);
        GraphExpansionCoverage coverage = new GraphExpansionCoverage(pattern.id(),
                EntityRef.wikidata("Q2"), relation,
                GraphTraversalDirection.INCOMING,
                GraphExpansionCoverage.State.ENCOUNTERED);
        return new GraphDiscoveryState(List.of(pattern), List.of(coverage));
    }
}
