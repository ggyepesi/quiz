package wikidata.explore.query.swing;

import work.QueryContext;

import java.awt.Component;

/**
 * Reusable Swing-facing query facility: one execution runner and one structured log
 * window over a shared {@link QueryContext}. Applications own a session and inject its
 * runner into panels; ModelBuilder and Transform therefore share execution, cancellation,
 * busy-state and logging without sharing application-specific workflows.
 */
public final class SwingQuerySession {

    private final WorkflowLogWindow logs = new WorkflowLogWindow();
    private final SwingQueryRunner runner;

    public SwingQuerySession(QueryContext context) {
        runner = new SwingQueryRunner(context, logs);
    }

    public SwingQueryRunner runner() {
        return runner;
    }

    public WorkflowLogWindow logs() {
        return logs;
    }

    public void showLogs(Component owner) {
        logs.show(owner);
    }

    public void info(String message) {
        logs.info(message);
    }
}
