package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * The graph renderer owns a JavaFX WebView inside the Explorer tab. The workbench owns
 * that tab and therefore owns renderer shutdown; this asserts the path exists and
 * survives being taken twice.
 */
class RendererShutdownTest {

    @Test void closingTheWorkbenchReleasesTheRenderer() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        model.addClass(person);
        model.rootClass(person);

        ModelSourceWorkbenchPanel workbench = new ModelSourceWorkbenchPanel(model);

        assertDoesNotThrow(workbench::close, "the workbench releases its helper tools");
        assertDoesNotThrow(workbench::close,
                "and shutting down twice is not a second kind of event");
    }
}
