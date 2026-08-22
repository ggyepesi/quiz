package wikidata.explore.query.workflow;

import wikidata.explore.query.core.*;
import work.*;
import work.LogListener;
import work.WorkflowRecorder;

import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

public class QueryWorkflow<R> {

    private final Supplier<Query<R>> querySupplier;
    private final QueryContext context;
    private final QueryResultSink<R> resultSink;
    private final LogListener logListener;

    public QueryWorkflow(
            QueryContext context,
            QueryResultSink<R> resultSink,
            LogListener logListener) {

        this(null, context, resultSink, logListener);
    }

    public QueryWorkflow(
            Supplier<Query<R>> querySupplier,
            QueryContext context,
            QueryResultSink<R> resultSink,
            LogListener logListener) {

        this.querySupplier = querySupplier;
        this.context = context;
        this.resultSink = resultSink;
        this.logListener = logListener;
    }

    public R run() throws Exception {
        return run(querySupplier.get());
    }

    public R run(Query<R> query) throws Exception {
        WorkflowRecorder recorder =
                WorkflowRecorder.forQuery(query);

        recorder.setListener(logListener);
        recorder.added();
        recorder.start();

        QueryContext runContext =
                context.withRecorder(recorder);

        R result;

        try {
            result = query.execute(runContext);
        } catch (Exception e) {
            recorder.finish(
                    isCancellation(e)
                            ? QueryStatus.CANCELLED
                            : QueryStatus.FAILED,
                    null,
                    e.getMessage());

            throw e;
        }

        recorder.finish(
                QueryStatus.OK,
                query.summary(result),
                null);

        resultSink.accept(result);
        return result;
    }

    public static boolean isCancellation(Throwable t) {
        for (; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException
                    || t instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }
}