package process;

import wikidata.explore.query.core.Query;

import java.util.Objects;

/** Adapts an existing Query without making either abstraction depend on the other. */
public final class QuerySubprocess<R> implements Process<R> {
    private final Query<R> query;

    public QuerySubprocess(Query<R> query) {
        this.query = Objects.requireNonNull(query, "query");
    }

    @Override public ProcessPlan plan() {
        return new ProcessPlan(
                query.purpose(), query.description(), query.parameters());
    }

    @Override public ProcessOutcome<R> execute(ProcessContext context) throws Exception {
        context.cancellation().throwIfCancelled();
        R result = query.execute(context.queries());
        context.cancellation().throwIfCancelled();
        return ProcessOutcome.succeeded(result, query.summary(result));
    }
}
