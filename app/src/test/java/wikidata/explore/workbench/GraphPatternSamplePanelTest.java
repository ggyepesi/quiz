package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphPatternSamplePanelTest {

    // The preview box holds at most three expansion nodes, so "reached but not in
    // this preview" is not the same question as "reached but not yet expanded". A
    // model with more configured seeds than the preview requested must not have its
    // own seeds reported back to it as frontier.
    @Test void aConfiguredSeedIsNotFrontierEvenWhenThisPreviewSkippedIt() {
        Set<String> frontier = GraphPatternSamplePanel.frontierQids(
                List.of("Q6412254", "Q181765", "Q253779", "Q29168087"),
                List.of("Q6412254"),
                List.of("Q6412254", "Q181765"));

        assertEquals(List.of("Q253779", "Q29168087"), List.copyOf(frontier),
                "only the genuinely unexpanded nodes, in the order reached");
    }

    @Test void nonQidSeedsCannotSuppressAReachedNode() {
        assertEquals(Set.of("Q253779"), GraphPatternSamplePanel.frontierQids(
                List.of("Q253779"), List.of(), List.of("", "not-a-qid")));
    }
}
