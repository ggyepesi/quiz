package domain.workbench.execution.ui;

import work.QueryContext;

import java.awt.Component;

/**
 * Reusable Swing-facing query facility: one execution runner and one structured log
 * window over a shared {@link QueryContext}. Applications own a session and inject its
 * runner into panels; ModelBuilder and Transform therefore share execution, cancellation,
 * busy-state and logging without sharing application-specific workflows.
 */
public final class SwingQuerySession implements AutoCloseable {

    private final WorkflowLogWindow logs;
    private final SwingQueryRunner runner;
    private Runnable cancelRequest = () -> { };
    private Runnable release = () -> { };

    public static SwingQuerySession openWorkbench() {
        String agent = "QuizProject/1.0 (ggyepesi@gmail.com)";
        var client = new wikidata.WikidataSparqlClient(agent, 2);
        var factory = new wikidata.query.core.QueryFactory(client, new wikidata.api.WikidataApiClient(agent), agent);
        var session = new SwingQuerySession(factory.newContext(
                java.nio.file.Path.of(aux.Constants.wikidataDataDirectory)));
        session.cancelRequest = client::cancelCurrentQuery;
        session.runner.cancelAction(session.cancelRequest);
        domain.workbench.ui.WikidataLabelLookup.install(session.runner);
        session.release = () -> {
            factory.close();
            client.close();
        };
        return session;
    }

    @Override public void close() {
        runner.cancel();
        release.run();
        release = () -> { };
    }

    public SwingQuerySession(QueryContext context) {
        this(context, new WorkflowLogWindow());
    }

    private SwingQuerySession(QueryContext context, WorkflowLogWindow logs) {
        this.logs = logs;
        runner = new SwingQueryRunner(context, logs);
    }

    /**
     * A second runner for a tool mounted in this session's workbench: it has its own
     * running state, but logs to this session's window and cancels this session's
     * in-flight request, so a mounted tool's work is neither missing from Query logs nor
     * uncancellable mid-request. It owns no clients; closing it only cancels its own run.
     */
    public SwingQuerySession companion() {
        var companion = new SwingQuerySession(runner.context(), logs);
        companion.cancelRequest = cancelRequest;
        companion.runner.cancelAction(cancelRequest);
        return companion;
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
