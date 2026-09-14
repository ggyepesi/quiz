package wikidata.explore.workbench;

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
                "src/main/java/wikidata/explore/workbench/ModelBuilderFrame.java"));
        int start = source.indexOf("generateButton.addActionListener");
        int end = source.indexOf("generateDomainButton.addActionListener", start);
        assertTrue(start >= 0 && end > start,
                "Generate class must own an explicit process-workflow action");
        String action = source.substring(start, end);

        assertTrue(action.contains("startGenerationOperation("),
                "the class run must use the same workflow host as other generation runs");
        assertTrue(action.contains("new GenerateInstancesQuery(compiledRun, "
                        + "executionSettings, depth)"),
                "the settings shown in the plan must be the settings execution consumes");
        assertFalse(source.contains("queryRunner.wireButton(\n                generateButton"),
                "the legacy query dialog must not remain as a second class-generation path");
    }
}
