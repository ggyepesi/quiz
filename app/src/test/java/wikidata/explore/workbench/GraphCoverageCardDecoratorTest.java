package wikidata.explore.workbench;

import datasource.EntityRef;
import datasource.graph.GraphDiscoveryState;
import datasource.graph.GraphExpansionCoverage;
import datasource.graph.GraphExpansionPattern;
import datasource.graph.GraphRelation;
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

    private static GraphDiscoveryState state() {
        GraphRelation relation = new GraphRelation("wikidata", "P39");
        GraphExpansionPattern pattern = new GraphExpansionPattern(
                "holding:P39:position", "Person", "Position", relation,
                "OfficeHolding", "source", "position");
        GraphExpansionCoverage coverage = new GraphExpansionCoverage(pattern.id(),
                EntityRef.wikidata("Q2"), relation,
                GraphExpansionCoverage.Direction.INCOMING,
                GraphExpansionCoverage.State.ENCOUNTERED);
        return new GraphDiscoveryState(List.of(pattern), List.of(coverage));
    }
}
