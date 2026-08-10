package batch;

/**
 * Where the executor reports to. Intentionally a tiny interface of its own rather than a
 * dependency on {@code GenerationLog}: that type lives in the ModelBuilder tree, and this
 * package has to serve curation just as well. Callers adapt their own log to it in a
 * couple of lambdas.
 *
 * <p>A unit is opened BEFORE it is issued, so a slow batch is visible while it runs
 * instead of appearing only once it returns — the behaviour {@code runRootQuery} already
 * established, and the reason a 60-second query does not look like a hang.
 */
public interface BatchProgress {

    /** An opened unit, finished exactly once by {@link #done} or {@link #failed}. */
    interface Running {
        void done(String summary);
        void failed(String error);
    }

    Running started(String title, String detail);

    default void message(String text) { }

    BatchProgress NOOP = (title, detail) -> new Running() {
        @Override public void done(String summary) { }
        @Override public void failed(String error) { }
    };
}
