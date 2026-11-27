package domain.workbench.execution.ui;

import dataset.DomainStorage;
import domain.config.BuildOperation;
import domain.config.ClassPopulationMode;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.transform.DynamicViewable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import process.swing.workflow.ProcessWorkflowPlan;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildProjectWorkflowTest {
    @TempDir Path directory;

    @Test void aBuildPlanShowsTheMissingTransformationBeforeExecution() {
        var project = new GeneratedProjectModel();
        project.name("Positions");
        var input = new GeneratedClassModel("Position");
        project.rootClass(input);
        var output = new GeneratedClassModel("ActivePosition");
        output.baseClassName(input.className());
        output.populationMode(ClassPopulationMode.MATERIALIZED_TRANSFORM);
        project.addClass(output);
        var save = new BuildOperation(BuildOperation.Kind.SAVE_PROJECT_RESULT, "");

        assertTrue(BuildProjectWorkflow.missingBuildWarnings(project)
                .contains("Run transformation ActivePosition"),
                "even a project with no steps says which transformation step is missing");
        project.buildOperations(List.of(save));

        var plan = plan(project);
        var warnings = plan.tabs().stream().filter(tab -> tab.title().startsWith("Warnings"))
                .findFirst().orElseThrow();
        assertTrue(warnings.initiallySelected(), "show the problem without a tab switch");
        var card = (DynamicViewable) warnings.cards().getFirst();
        assertTrue(card.get("Detail").toString().contains("Run transformation ActivePosition"));
        assertTrue(plan.executable(), "a missing transformation is a warning, not a refusal");
        assertEquals(List.of(save.declarationId()), project.buildOperations().stream()
                .map(BuildOperation::declarationId).toList(), "planning does not add the step");

        project.buildOperations(List.of(new BuildOperation(BuildOperation.Kind.RUN_TRANSFORMATION,
                output.declarationId()), save));
        assertFalse(plan(project).tabs().stream().anyMatch(tab -> tab.title().startsWith("Warnings")));
    }

    private ProcessWorkflowPlan plan(GeneratedProjectModel project) {
        var storage = DomainStorage.in(directory.toFile());
        return BuildProjectWorkflow.action(project, storage.modelFile(project.name()), storage, null,
                "Reload", ignored -> new BuildProjectWorkflow.Loaded<>(null, ""), ignored -> {},
                "Inspect instances").plan();
    }
}
