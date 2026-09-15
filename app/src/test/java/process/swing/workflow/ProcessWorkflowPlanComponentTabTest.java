package process.swing.workflow;

import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A workflow plan may explain spatial work spatially instead of disguising it as cards. */
class ProcessWorkflowPlanComponentTabTest {
    @Test void componentTabKeepsItsContentSeparateFromCardRendering() {
        ProcessWorkflowPlan.Tab tab = ProcessWorkflowPlan.Tab.component(
                "Graph", () -> new JLabel("A → B"), true);

        assertEquals("Graph", tab.title());
        assertTrue(tab.cards().isEmpty());
        assertNotNull(tab.content());
        assertEquals("A → B", ((JLabel) tab.content().get()).getText());
        assertTrue(tab.initiallySelected());
    }

    @Test void preferredGraphTabOpensInsteadOfLeadingPipelineTab() {
        ProcessWorkflowPlan.Tab graph = ProcessWorkflowPlan.Tab.component(
                "Graph", () -> new JLabel("A → B"), true);
        ProcessWorkflowPlan.Tab scope = new ProcessWorkflowPlan.Tab("Scope", List.of());

        assertEquals(1, SwingProcessWorkflow.initialPlanTabIndex(
                List.of(graph, scope), 1, true));
    }

    @Test void aSpatialPlanCanOmitTheRedundantPipelineTabOnlyFromItsPlan() {
        ProcessWorkflowPlan plan = new ProcessWorkflowPlan(
                "Run graph", "Inspect the graph", List.of()).withoutPipelineTab();

        assertFalse(plan.pipelineVisible());
    }
}
