package wikidata.explore.query.log;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowRecorderErrorTest {

    @Test void nullIsNull() {
        assertEquals(null, WorkflowRecorder.describeError(null));
    }

    @Test void messagelessExceptionFallsBackToClassName() {
        // A dropped connection / timeout often has a null message — must NOT
        // render as a bare blank (the "only says Failed" bug).
        String d = WorkflowRecorder.describeError(new IOException());
        assertEquals("IOException", d);
    }

    @Test void usesMessageWhenPresent() {
        assertEquals("SPARQL HTTP 500",
                WorkflowRecorder.describeError(new RuntimeException("SPARQL HTTP 500")));
    }

    @Test void unwrapsCauseChainWithoutDuplicating() {
        RuntimeException cause = new RuntimeException("SPARQL HTTP 500");
        // ExecutionException's message embeds the cause's toString().
        ExecutionException wrapped = new ExecutionException(cause);
        String d = WorkflowRecorder.describeError(wrapped);
        assertTrue(d.contains("SPARQL HTTP 500"), d);
        // The duplicate "caused by" of the same text is suppressed.
        assertEquals(d.indexOf("SPARQL HTTP 500"), d.lastIndexOf("SPARQL HTTP 500"), d);
    }
}
