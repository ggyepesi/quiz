package wikidata.explore.extract;

import java.util.function.Consumer;

/**
 * Progress sink for a generation run. {@link #message} is free-text progress;
 * {@link #subquery} records ONE sub-query as a structured, collapsible entry
 * (e.g. each per-parent child query) instead of appending it to an
 * ever-growing log blob.
 */
public interface GenerationLog {

    void message(String text);

    void subquery(String title, String request, String summary);

    /** Records ONE sub-query that FAILED (e.g. a per-parent timeout) as a
     *  structured child entry, so the failing step is visible under the same
     *  log entry instead of vanishing when the query throws. Default keeps
     *  text-only sinks working by flattening into {@link #subquery}. */
    default void subqueryFailed(String title, String request, String error) {
        subquery(title, request, "FAILED: " + (error == null ? "" : error));
    }

    /** A sub-query shown in the log WHILE it runs: {@link #done}/{@link #failed}
     *  finish it. Returned by {@link #subqueryStarted}. */
    interface Running {
        void done(String summary);
        void failed(String error);
    }

    /**
     * Opens a sub-query in the log <em>before</em> it is issued, so the running
     * query is visible instead of appearing only once it returns. The caller must
     * finish it via the returned handle. Default: no live node — the handle records
     * the query atomically on {@link Running#done}/{@link Running#failed}, so sinks
     * that don't support a running state behave exactly as before.
     */
    default Running subqueryStarted(String title, String request) {
        return new Running() {
            @Override public void done(String summary) {
                subquery(title, request, summary);
            }
            @Override public void failed(String error) {
                subqueryFailed(title, request, error);
            }
        };
    }

    /**
     * This log as a wbgetentities batch sink.
     *
     * <p>Exists so a refused batch records through {@link #subqueryFailed} instead of
     * {@link #subquery}: passing {@code this::subquery} as the sink — which every
     * caller did — filed failures as OK entries whose text merely began with "FAILED",
     * and a run with 54 refused batches then read as clean.
     */
    default wikidata.api.WikidataApiClient.BatchLog batchSink() {
        GenerationLog self = this;
        return new wikidata.api.WikidataApiClient.BatchLog() {
            @Override public void message(String text) { self.message(text); }
            @Override public Running started(String title, String request) {
                GenerationLog.Running visible = self.subqueryStarted(title, request);
                java.util.List<String> details = new java.util.ArrayList<>();
                return new Running() {
                    @Override public void detail(String text) { details.add(text); }
                    @Override public void done(String summary) {
                        visible.done(wikidata.api.WikidataApiClient.BatchLog
                                .withDetails(details, summary));
                    }
                    @Override public void failed(String error) {
                        visible.failed(wikidata.api.WikidataApiClient.BatchLog
                                .withDetails(details, error));
                    }
                };
            }
            @Override public void logged(String title, String request, String summary) {
                self.subquery(title, request, summary);
            }
            @Override public void failed(String title, String request, String error) {
                self.subqueryFailed(title, request, error);
            }
        };
    }

    /**
     * A named, collapsible group of sub-entries: sub-queries/messages logged on it
     * nest under one parent node, so a batched load (Qualifier load 1/8, 2/8, …)
     * reads as one expandable top-level item instead of N flat rows. Close it
     * (try-with-resources) to finalize the parent.
     */
    interface Group extends GenerationLog, AutoCloseable {
        @Override void close();
    }

    /**
     * Opens a {@link Group} titled {@code title}. Default: no nesting — a
     * pass-through that logs onto {@code this} and closes as a no-op, so text
     * sinks and NOOP are unaffected in structure.
     *
     * <p>The title is still emitted as a message. It states what the group's
     * requests are FOR, and a sink that cannot nest would otherwise show a run of
     * batches with nothing saying what they were filling.
     */
    default Group group(String title) {
        GenerationLog self = this;
        if (title != null && !title.isBlank()) {
            self.message(title + "\n");
        }
        return new Group() {
            @Override public void message(String t) { self.message(t); }
            @Override public void subquery(String ti, String r, String s) {
                self.subquery(ti, r, s);
            }
            @Override public void subqueryFailed(String ti, String r, String e) {
                self.subqueryFailed(ti, r, e);
            }
            @Override public Running subqueryStarted(String ti, String r) {
                return self.subqueryStarted(ti, r);
            }
            @Override public void close() {}
        };
    }

    GenerationLog NOOP = new GenerationLog() {
        @Override public void message(String text) {}
        @Override public void subquery(String t, String r, String s) {}
    };

    /** Adapts a plain text sink (subqueries are flattened into messages). */
    static GenerationLog of(Consumer<String> sink) {
        if (sink == null) {
            return NOOP;
        }
        return new GenerationLog() {
            @Override public void message(String text) { sink.accept(text); }
            @Override public void subquery(String title, String request, String summary) {
                sink.accept("\n" + title + "  " + (summary == null ? "" : summary)
                        + "\n" + (request == null ? "" : request) + "\n");
            }
        };
    }
}
