package batch;

/**
 * A completed work value whose terminal outcome may be a stated refusal rather than a
 * successful result.
 *
 * <p>The value is still committed: callers need it to preserve the refusal and produce a
 * partial aggregate result. The executor uses the same fact to finish the individual work
 * entry as failed instead of recording it as {@code OK}.
 */
public interface BatchOutcome {

    /** Why this completed unit was refused, or an empty string when it succeeded. */
    String failure();
}
