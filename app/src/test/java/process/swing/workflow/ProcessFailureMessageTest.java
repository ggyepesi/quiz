package process.swing.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
