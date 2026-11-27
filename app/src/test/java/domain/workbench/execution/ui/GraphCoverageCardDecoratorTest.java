package domain.workbench.execution.ui;

import domain.datasource.EntityRef;
import domain.datasource.graph.GraphDiscoveryState;
import domain.datasource.graph.GraphExpansionCoverage;
import domain.datasource.graph.GraphExpansionPattern;
import domain.datasource.graph.GraphRelation;
import domain.datasource.graph.GraphTraversalDirection;
import domain.datasource.graph.GraphTraversalStep;
import domain.datasource.graph.GraphExpansionPolicy;
import org.junit.jupiter.api.Test;
import domain.runtime.WikidataDynamicObject;

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
