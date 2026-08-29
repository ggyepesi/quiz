package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * The graph renderer holds a viewer thread, and the window it lives in hides rather
 * than disposes so an explored graph survives a close and reopen. Nothing inside that
 * window can therefore decide when the renderer is finished — the workbench says so on
 * the way out, and this asserts the path exists and survives being taken twice.
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
