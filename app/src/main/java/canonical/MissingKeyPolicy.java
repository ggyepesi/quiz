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

    /**
     * Grouping is the default, by the same rule that decides a reducer's: a default may
     * only be non-destructive.
     *
     * <p>Rejecting was the first answer here, on the grounds that a counted drop is not
     * a silent one. Run over the shipped snapshots it would have discarded 99 real
     * records — 56 Oscar nominations with no ceremony, 36 office holdings with no dates,
     * 7 Nobel awards with no motivation — which is destructive however loudly it is
     * counted, and a default may not be. It is also what the existing paths do: a blank
     * component simply forms part of the key string, so those records already group.
     *
     * <p>Rejecting remains available, and is the right choice for a class where a
     * candidate without a key is not a record at all. That is a decision, not a default.
     */
    public static MissingKeyPolicy defaultPolicy() {
        return INCOMPLETE_GROUP;
    }
}
