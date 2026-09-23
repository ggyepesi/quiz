package process.swing.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessFailureMessageTest {
    @Test void anExceptionWithoutAMessageStillNamesTheFailure() {
        assertEquals("UnsupportedOperationException",
                SwingProcessWorkflow.failureMessage(
                        new UnsupportedOperationException()));
    }

    @Test void anExceptionMessageRemainsTheUsefulExplanation() {
        assertEquals("bad configuration",
                SwingProcessWorkflow.failureMessage(
                        new IllegalArgumentException("bad configuration")));
    }

    @Test void aLinkageFailureNamesItsTypeInsteadOfLookingLikeAClassResult() {
        assertEquals("NoClassDefFoundError: wikidata/example/Missing",
                SwingProcessWorkflow.failureMessage(
                        new NoClassDefFoundError("wikidata/example/Missing")));
    }

    @Test void aFailedOutcomeExplicitlySaysThatNoResultExists() {
        String shown = SwingProcessWorkflow.failedOutcomeMessage(
                process.ProcessOutcome.failed(
                        new NoClassDefFoundError("wikidata/example/Missing")));

        assertTrue(shown.startsWith("Process failed. No result was produced."), shown);
        assertTrue(shown.contains("NoClassDefFoundError"), shown);
    }
}
