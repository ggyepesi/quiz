package process;

import org.junit.jupiter.api.Test;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.log.LogNode;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ProcessInfrastructureTest {

    @Test
    void childCancellationDoesNotCancelParentOrSibling() {
        CancellationToken parent = new CancellationToken();
        CancellationToken first = parent.child();
        CancellationToken second = parent.child();

        first.cancel();

        assertTrue(first.isCancelled());
        assertFalse(parent.isCancelled());
        assertFalse(second.isCancelled());
    }

    @Test
    void cancellationListenersAreRaceSafeAndCanBeDetached() {
        CancellationToken token = new CancellationToken();
        AtomicInteger calls = new AtomicInteger();
        CancellationToken.Registration detached = token.onCancel(calls::incrementAndGet);
        detached.close();
        token.onCancel(calls::incrementAndGet);

        token.cancel();
        token.onCancel(calls::incrementAndGet);

        assertEquals(2, calls.get(),
                "one attached listener and one late listener run exactly once");
    }

    @Test
    void queryIsAdaptedAsHierarchicalSubprocessAndEveryLogTerminates() {
        AtomicReference<LogNode> root = new AtomicReference<>();
        Query<String> query = new Query<>() {
            public String purpose() { return "Child query"; }
            public String skeleton() { return "test"; }
            public Map<String, String> parameters() { return Map.of(); }
            public String execute(QueryContext context) throws Exception {
                return context.step("Network request", "HTTP", "GET", Map.of(),
                        step -> "kept");
            }
            public int rowCount(String result) { return 1; }
        };
        Process<String> parent = new Process<>() {
            public ProcessPlan plan() {
                return new ProcessPlan("Parent", "", Map.of());
            }
            public ProcessOutcome<String> execute(ProcessContext context) {
                return context.run(new QuerySubprocess<>(query));
            }
        };

        ProcessOutcome<String> outcome = new ProcessRunner(
                new QueryContext(null, null),
                (node, added) -> root.set(node),
                ProcessInputHandler.unsupported())
                .run(parent, new CancellationToken());

        assertEquals(ProcessStatus.SUCCEEDED, outcome.status());
        assertEquals("kept", outcome.result());
        assertNotNull(root.get());
        assertAllTerminal(root.get());
        assertEquals(1, root.get().steps().size());
        assertEquals(1, root.get().steps().iterator().next().steps().size(),
                "query detail must be nested beneath its subprocess");
    }

    @Test
    void abandonedNestedLogIsTerminallyClosedWhenProcessFails() {
        AtomicReference<LogNode> root = new AtomicReference<>();
        Process<Void> process = new Process<>() {
            public ProcessPlan plan() {
                return new ProcessPlan("Broken", "", Map.of());
            }
            public ProcessOutcome<Void> execute(ProcessContext context) {
                return context.run(new Process<>() {
                    public ProcessPlan plan() {
                        return new ProcessPlan("Child", "", Map.of());
                    }
                    public ProcessOutcome<Void> execute(ProcessContext ignored) {
                        throw new IllegalStateException("boom");
                    }
                });
            }
        };

        new ProcessRunner(new QueryContext(null, null),
                (node, added) -> root.set(node), ProcessInputHandler.unsupported())
                .run(process, new CancellationToken());

        assertAllTerminal(root.get());
    }

    private static void assertAllTerminal(LogNode node) {
        assertTrue(node.status().isTerminal(), node.getDisplayName());
        node.steps().forEach(ProcessInfrastructureTest::assertAllTerminal);
    }
}
