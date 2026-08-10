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
 * <p>Implementations accumulate their results wherever the caller wants them (the way
 * {@code CompanionLoader.loadWithSplit} collected into a set); the executor is concerned
 * only with getting every unit to complete, not with what they produce.
 */
public interface WorkUnit {

    /** Stable identity, used for checkpointing and for the log. Must be reproducible
     *  across runs: a resumed run recognises completed work by this key. */
    String key();

    /** Human-readable title for the progress log, e.g. "members 200-299". */
    String title();

    /** Performs the work. Throwing hands the failure to the executor to classify. */
    void run() throws Exception;

    /**
     * This unit divided into smaller ones, or an empty list when it cannot be divided
     * (a single member, an exhausted range). Splitting must COVER the original exactly:
     * the executor treats the parts as replacing the whole, so anything the parts do not
     * cover is silently lost.
     */
    List<WorkUnit> split();
}
