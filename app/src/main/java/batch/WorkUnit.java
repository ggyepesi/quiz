package batch;

import java.util.List;

/**
 * One batch of work that can be narrowed if it proves too heavy.
 *
 * <p>Two things make a unit: it can RUN, and it can SPLIT. Splitting is what turns a
 * query the endpoint refuses to finish into queries it will — a smaller query is a
 * different query, which is why re-issuing the original cannot help. A unit that cannot
 * be divided further returns an empty split, and the executor then has nowhere left to
 * escalate.
 *
 * <p>Implementations return detached results. The executor hands a result to its
 * {@link ResultCommitter} only after the whole unit succeeds; registry/graph mutation is
 * therefore single-threaded and never performed by an attempt that may be retried.
 */
public interface WorkUnit<R> {

    /** Durable identity and reconstruction data for this exact partition of the work. */
    WorkDescriptor descriptor();

    default String key() {
        return descriptor().key();
    }

    default String title() {
        return descriptor().title();
    }

    /**
     * The request this unit issues — the SPARQL text, the API URL — for the log to show
     * and link to. It is DISPLAY only: reconstruction after a restart goes through
     * {@link #descriptor()}, whose parameters are what a {@link WorkUnitFactory} needs.
     *
     * <p>Empty by default. A unit whose work has no request text (a pure computation)
     * leaves it empty and the log simply shows no request — which is what a key placed
     * in this slot used to imitate, producing an unusable "query" link.
     */
    default String request() {
        return "";
    }

    /**
     * Computes this unit's result without mutating the shared registry, graph or caller's
     * result collection. The executor publishes the returned value only after this method
     * completes successfully, so a failed attempt can be retried without duplicating a
     * partially applied result.
     */
    R execute() throws Exception;

    /**
     * This unit divided into smaller ones, or an empty list when it cannot be divided
     * (a single member, an exhausted range). Splitting must COVER the original exactly:
     * the executor treats the parts as replacing the whole, so anything the parts do not
     * cover is silently lost.
     */
    List<? extends WorkUnit<R>> split();
}
