package process;

import org.junit.jupiter.api.Test;
import work.CancellationToken;
import work.QueryContext;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** A saved UI summary is not a replacement for a complete technical diagnostic. */
class ProcessFailureStderrTest {

    @Test void aChildFailureConvertedToAnOutcomeStillPrintsItsFullStackOnce() {
        RuntimeException failure = new RuntimeException("mapping failed",
                new StackOverflowError("deep graph"));
        Process<Void> child = new Process<>() {
            @Override public ProcessPlan plan() {
                return new ProcessPlan("Child", "fails", Map.of());
            }
            @Override public ProcessOutcome<Void> execute(ProcessContext context) {
                throw failure;
            }
        };
        Process<Void> root = new Process<>() {
            @Override public ProcessPlan plan() {
                return new ProcessPlan("Root", "runs child", Map.of());
            }
            @Override public ProcessOutcome<Void> execute(ProcessContext context) {
                return context.run(child);
            }
        };
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try {
            System.setErr(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            new ProcessRunner(new QueryContext(), (node, added, completed) -> { },
                    ProcessInputHandler.unsupported())
                    .run(root, new CancellationToken());
        } finally {
            System.setErr(previous);
        }

        String diagnostic = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(diagnostic.contains("RuntimeException: mapping failed"), diagnostic);
        assertTrue(diagnostic.contains("Caused by: java.lang.StackOverflowError: deep graph"),
                diagnostic);
        assertTrue(diagnostic.contains("ProcessFailureStderrTest"), diagnostic);
    }
}
