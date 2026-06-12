package wikidata.explore.query.workflow;

import wikidata.explore.query.core.*;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class QueryWorkflow<R> {

    private static final AtomicLong IDS = new AtomicLong();

    private final Supplier<Query<R>> querySupplier;
    private final QueryContext context;
    private final QueryResultSink<R> resultSink;
    private final QueryEventSink eventSink;

    public QueryWorkflow(
            QueryContext context,
            QueryResultSink<R> resultSink,
            QueryEventSink eventSink) {

        this(null, context, resultSink, eventSink);
    }

    public QueryWorkflow(
            Supplier<Query<R>> querySupplier,
            QueryContext context,
            QueryResultSink<R> resultSink,
            QueryEventSink eventSink) {

        this.querySupplier = querySupplier;
        this.context = context;
        this.resultSink = resultSink;
        this.eventSink = eventSink;
    }

    public R run() throws Exception {
        return run(querySupplier.get());
    }

    /**
     * Runs a pre-built query. Build the query on the EDT (capturing a
     * model snapshot and any widget state) and call this off the EDT.
     */
    public R run(Query<R> query) throws Exception {
        long id = IDS.incrementAndGet();
        long start = System.currentTimeMillis();

        eventSink.accept(new QueryEvent(
                id,
                query,
                QueryStatus.RUNNING,
                -1,
                0,
                ""));

        R result;
        try {
            result = query.execute(context);

        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;

            eventSink.accept(new QueryEvent(
                    id,
                    query,
                    isCancellation(e)
                            ? QueryStatus.CANCELLED
                            : QueryStatus.FAILED,
                    0,
                    ms,
                    e.getMessage() == null ? "" : e.getMessage()));

            throw e;
        }

        long ms = System.currentTimeMillis() - start;

        eventSink.accept(new QueryEvent(
                id,
                query,
                QueryStatus.OK,
                query.rowCount(result),
                ms,
                ""));

        resultSink.accept(result);
        return result;
    }

    private static boolean isCancellation(Throwable t) {
        for (; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException
                    || t instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }
}