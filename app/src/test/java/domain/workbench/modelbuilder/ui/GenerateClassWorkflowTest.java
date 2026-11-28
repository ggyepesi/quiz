package domain.workbench.modelbuilder.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generate class used the legacy query-button path while Generate domain opened the
 * shared Plan → Running → Results workflow. That hid execution settings and applied a
 * completed class run without the workflow's explicit review boundary.
 */
class GenerateClassWorkflowTest {

    @Test
    void classGenerationUsesTheSharedProcessWorkflowNotTheLegacyQueryButton()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/domain/workbench/modelbuilder/ui/ModelBuilderFrame.java"));
        int start = source.indexOf("private GenerationWorkflows.Action prepareClassGeneration()");
        int end = source.indexOf("\n    private ", start + 1);
        assertTrue(start >= 0 && end > start,
                "Generate class must own an explicit process-workflow action");
        String action = source.substring(start, end);

        assertTrue(source.contains("() -> startGeneration(this::prepareClassGeneration)"),
                "the class run must use the same workflow host as other generation runs");
        assertTrue(action.contains("generationWorkflows.generateClass("),
                "ModelBuilder delegates to the shared Generate class workflow");
        assertTrue(source.contains("generationWorkflows.generateDomain(")
                        && source.contains("generationWorkflows.remap(")
                        && source.contains("generationWorkflows.enrich("),
                "the other buttons also delegate rather than assembling workflows in the frame");
        assertFalse(source.contains("new GenerateInstancesQuery("),
                "the legacy query dialog must not remain as a second class-generation path");
    }
}
