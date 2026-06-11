package wikidata.explore.query.workflow;

import wikidata.explore.query.core.*;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class QueryWorkflow<R> {

    private static final AtomicLong IDS = new AtomicLong();

    private final Supplier<Query<R>> querySupplier;
    private final QueryContext context;
    private final QueryResultSink<R> resultSink;
    private final QueryEventSink eventSink;

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
        Query<R> query = querySupplier.get();

        long id = IDS.incrementAndGet();
        long start = System.currentTimeMillis();

        eventSink.accept(new QueryEvent(
                id,
                query,
                QueryStatus.RUNNING,
                -1,
                0,
                ""));

        try {
            R result = query.execute(context);
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

        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;

            eventSink.accept(new QueryEvent(
                    id,
                    query,
                    QueryStatus.FAILED,
                    0,
                    ms,
                    e.getMessage() == null ? "" : e.getMessage()));

            throw e;
        }
    }
}