package process.swing.workflow;

import org.junit.jupiter.api.Test;

import javax.swing.JLabel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A workflow plan may explain spatial work spatially instead of disguising it as cards. */
class ProcessWorkflowPlanComponentTabTest {
    @Test void componentTabKeepsItsContentSeparateFromCardRendering() {
        ProcessWorkflowPlan.Tab tab = ProcessWorkflowPlan.Tab.component(
                "Graph", () -> new JLabel("A → B"));

        assertEquals("Graph", tab.title());
        assertTrue(tab.cards().isEmpty());
        assertNotNull(tab.content());
        assertEquals("A → B", ((JLabel) tab.content().get()).getText());
    }
}
