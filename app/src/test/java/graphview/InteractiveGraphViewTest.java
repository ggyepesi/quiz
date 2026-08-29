package graphview;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveGraphViewTest {
    @Test void localPageProvidesVisibleEdgesCardsLinksAndExplicitControls() {
        String page = InteractiveGraphView.page();
        assertAll(() -> assertTrue(page.contains("cytoscape({")),
                () -> assertTrue(page.contains("nodeHtmlLabel")),
                () -> assertTrue(page.contains("enablePointerEvents:true")),
                () -> assertTrue(page.contains("class=\"node-link\"")),
                () -> assertTrue(page.contains("function collapseSelected()")),
                () -> assertFalse(page.contains("__CYTOSCAPE__")));
    }

    @Test void modelJsonPreservesProviderNeutralNodeAndEdgeMeaning() throws Exception {
        GraphViewModel model = new GraphViewModel(
                List.of(new GraphViewModel.Node("Q1", "One", java.net.URI.create("https://example.test/Q1"),
                        2, GraphViewModel.State.FRONTIER, Map.of("Jurisdiction", "Hungary"), new Object())),
                List.of(new GraphViewModel.Edge("P39", "Q1", "Q2", "position held", true)));
        JsonNode json = new ObjectMapper().readTree(InteractiveGraphView.modelJson(model));
        assertAll(() -> assertEquals("One", json.at("/nodes/0/data/label").asText()),
                () -> assertEquals("Hungary", json.at("/nodes/0/data/details/Jurisdiction").asText()),
                () -> assertEquals("position held", json.at("/edges/0/data/label").asText()),
                () -> assertTrue(json.at("/edges/0/data/directed").asBoolean()));
    }

    // The renderer's assets are named twice: as a Maven version and as a path inside
    // the WebJar. Bumping the pom leaves the path pointing at something that is no
    // longer there, and the loader says so — but only when a reader opens the view.
    // This moves that to the build, which is where the version is changed.
    @Test void theRenderersAssetsResolveOnTheClasspath() {
        for (String resource : InteractiveGraphView.assetPaths()) {
            assertNotNull(InteractiveGraphView.class.getResourceAsStream(resource),
                    "missing WebJar asset — has the version in pom.xml moved? " + resource);
        }
    }
}
