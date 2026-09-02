package wikidata.explore.workbench;

import datasource.EntityRef;
import datasource.graph.GraphDiscoveryState;
import datasource.graph.GraphExpansionCoverage;
import datasource.graph.GraphExpansionPattern;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import org.junit.jupiter.api.Test;
import process.ProcessOutcome;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphFrontierWorkflowActionTest {

    @Test void nestedReferenceOnlyFrontierNodeStillGetsAResultCard() {
        GraphRelation relation = new GraphRelation("wikidata", "P39");
        GraphExpansionPattern pattern = new GraphExpansionPattern(
                "holding:P39:position", "Person", "Position", relation,
                "OfficeHolding", "source", "position", GraphTraversalDirection.INCOMING);
        GraphExpansionCoverage frontier = new GraphExpansionCoverage(pattern.id(),
                EntityRef.wikidata("Q253779"), relation,
                GraphTraversalDirection.INCOMING,
                GraphExpansionCoverage.State.ENCOUNTERED);
        GraphDiscoveryState state = new GraphDiscoveryState(
                List.of(pattern), List.of(frontier));

        WikidataDynamicObject position = object("Q253779", "Ban of Croatia", "Position");
        WikidataDynamicObject holding = object("Q82686$holding", "Holding", "OfficeHolding");
        holding.put("position", position);

        AtomicBoolean continued = new AtomicBoolean();
        GraphFrontierWorkflowAction action = new GraphFrontierWorkflowAction(
                state, List.of(pattern), List.of(holding),
                ignored -> { }, () -> continued.set(true));
        var results = action.results(ProcessOutcome.succeeded(state, "ready"));

        assertEquals(1, results.tabs().get(1).cards().size());
        assertEquals("Q253779", ((WikidataDynamicObject) results.tabs().get(1)
                .cards().getFirst().view()).qid());
        action.afterApply();
        assertEquals(true, continued.get());
    }

    private static WikidataDynamicObject object(String qid, String name, String type) {
        WikidataDynamicObject object = new WikidataDynamicObject(qid, name);
        object.type(type);
        return object;
    }
}
