package canonical;

/**
 * What becomes of a candidate whose key cannot be computed.
 *
 * <p>A different question from a field conflict, and the difference is scope: this
 * decides whether a candidate takes part AT ALL, before any partition forms, while a
 * conflict is about one field of a partition that already exists. That is why this is
 * configured beside the key and a conflict is not configured at all — the reducer choice
 * already says what disagreement means.
 */
public enum MissingKeyPolicy {

    /** Leave the candidate out, and count what was left out. */
    REJECT_CANDIDATE,

    /** Group it with the other candidates missing the same component, and say so. */
    INCOMPLETE_GROUP,

    /** Stop: for this class, a candidate without a key means the run is wrong. */
    FAIL;

    /** Rejecting is the default: it drops nothing silently, because it is counted. */
    public static MissingKeyPolicy defaultPolicy() {
        return REJECT_CANDIDATE;
    }
}
