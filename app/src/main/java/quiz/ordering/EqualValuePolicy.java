package quiz.ordering;

/**
 * How a placement behaves when two cards have the same ordering value.
 */
public enum EqualValuePolicy {
    /** Every gap within the tied block is correct. */
    EQUIVALENT,
    /**
     * Generation ties use the item identifier as a stable secondary key.
     * Value-only placement cannot apply this policy: the current
     * PlacementEvaluator deliberately rejects it until placement receives
     * candidate and board item identifiers as well as values.
     */
    STABLE_BY_ID
}
