package wikidata.explore.query.logical;

import wikidata.explore.extract.GenerationLog;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.log.LogNode;
import wikidata.explore.query.log.LogStep;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link GenerationLog} that records into the query log's TREE, not just its text.
 *
 * <p>The difference decides whether a long run can be watched. A flat log only prints
 * what has already happened, so a fetch over thousands of entities shows nothing until
 * each batch returns — and nothing at all while one hangs. Recording as steps opens a
 * child entry when a request STARTS and completes it when it returns, so an in-flight
 * request is visible as itself, and groups report how many requests they made.
 */
final class StepGenerationLog {

    private StepGenerationLog() {}

    static GenerationLog of(QueryContext context, LogStep step) {
        return of(context, step, null);
    }

    /** With {@code echoPrefix}, every message is also printed to stdout with a
     *  timestamp — for a run whose progress must be readable outside the app. */
    static GenerationLog of(QueryContext context, LogStep step, String echoPrefix) {
        return new GenerationLog() {
            @Override public void message(String text) {
                context.message(text);
                echo(echoPrefix, text);
            }
            @Override public void subquery(String title, String request, String summary) {
                step.subquery(title, request, summary);
                echo(echoPrefix, title + "  " + summary);
            }
            @Override public void subqueryFailed(String title, String request, String error) {
                step.subqueryFailed(title, request, error);
                echo(echoPrefix, title + "  FAILED " + error);
            }
            @Override public Running subqueryStarted(String title, String request) {
                echo(echoPrefix, title + "  started");
                return running(step, title, request, echoPrefix);
            }
            @Override public Group group(String title) {
                echo(echoPrefix, title);
                LogStep sub = step.beginGroup(title);
                return new Group() {
                    // Batches within a group can record concurrently, so count atomically.
                    private final AtomicInteger n = new AtomicInteger();
                    @Override public void message(String text) {
                        context.message(text);
                        echo(echoPrefix, text);
                    }
                    @Override public void subquery(String ti, String r, String s) {
                        sub.subquery(ti, r, s);
                        n.incrementAndGet();
                        echo(echoPrefix, ti + "  " + s);
                    }
                    @Override public void subqueryFailed(String ti, String r, String e) {
                        sub.subqueryFailed(ti, r, e);
                        n.incrementAndGet();
                        echo(echoPrefix, ti + "  FAILED " + e);
                    }
                    @Override public Running subqueryStarted(String ti, String r) {
                        n.incrementAndGet();
                        echo(echoPrefix, ti + "  started");
                        return running(sub, ti, r, echoPrefix);
                    }
                    @Override public void close() {
                        sub.completeGroup(n.get() + " request(s)");
                        echo(echoPrefix, title + " — " + n.get() + " request(s)");
                    }
                };
            }
        };
    }

    private static GenerationLog.Running running(
            LogStep step, String title, String request, String echoPrefix) {
        LogNode child = step.beginSubquery(title, request);
        return new GenerationLog.Running() {
            @Override public void done(String summary) {
                step.completeSubquery(child, summary);
                echo(echoPrefix, title + "  " + summary);
            }
            @Override public void failed(String error) {
                step.failSubquery(child, error);
                echo(echoPrefix, title + "  FAILED " + error);
            }
        };
    }

    private static void echo(String prefix, String text) {
        if (prefix == null || text == null || text.isBlank()) return;
        String stamp = java.time.LocalTime.now().withNano(0).toString();
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) System.out.println("[" + prefix + " " + stamp + "] " + line);
        }
    }
}
