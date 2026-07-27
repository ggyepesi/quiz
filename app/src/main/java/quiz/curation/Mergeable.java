package quiz.curation;

/**
 * A domain that can apply a {@link Merge} to its underlying <em>persistent</em> instance
 * pool. Needed because a view layer (e.g. the transform WorkingDomain) may hand out a
 * throwaway combined copy from {@code instances()} — so a merge's REMOVAL of the
 * duplicate has to target the real pool to take effect in the live session, not just on
 * the next reload.
 */
public interface Mergeable {

    /** Instances whose merge can be applied to durable domain state. */
    java.util.Collection<? extends quiz.Quizable> mergeableInstances();

    /** Apply {@code merge} to the real pool; returns how many merges took effect (0/1). */
    int applyMerge(Merge merge);
}
