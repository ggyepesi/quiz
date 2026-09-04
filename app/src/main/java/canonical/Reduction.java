package canonical;

/**
 * What happens to a non-key field when several candidates share one key.
 *
 * <p>The vocabulary is deliberately small, and deliberately split by one property:
 * whether a choice can lose something without saying so. {@link #nonDestructive()} is not
 * a description, it is the rule that decides what may be a DEFAULT — a default may only
 * be non-destructive, which is also why a key must be chosen while a reducer need not be.
 */
public enum Reduction {

    /**
     * Every non-empty value must be semantically equal; disagreement is reported.
     * Keeps one value and can lose nothing quietly, so it is the safe default.
     */
    REQUIRE_AGREEMENT,

    /**
     * Flatten and keep the distinct values. Keeps everything, so it loses nothing —
     * and it is the only sane reading for a field that already holds many values.
     */
    UNION_DISTINCT,

    /**
     * Take the one non-empty value. Two conflicting non-empty values are reported
     * rather than ranked, but an empty value IS discarded, so this is chosen.
     */
    PREFER_NON_EMPTY,

    /**
     * Take the value an explicitly configured ordering or evidence policy selects.
     * "First encountered" is not a policy. Always chosen; never a default.
     */
    CHOOSE_BY_POLICY;

    /**
     * Whether this can only keep or report, never silently discard.
     *
     * <p>The test a default has to pass. {@code REQUIRE_AGREEMENT} keeps one value and
     * reports when candidates disagree; {@code UNION_DISTINCT} keeps them all. The other
     * two drop a value, so a modeller has to ask for them.
     */
    public boolean nonDestructive() {
        return this == REQUIRE_AGREEMENT || this == UNION_DISTINCT;
    }

    /**
     * What a field gets when nobody has chosen.
     *
     * <p>Cardinality decides it, and mostly decides what is even valid: a union on a
     * single-valued field would produce a list the field cannot hold. An undetermined
     * cardinality takes the same answer as single, and its failure mode is informative —
     * a conflict there is evidence the field IS a collection, which is what cardinality
     * detection exists to discover.
     *
     * @param holdsManyValues whether the field's declared cardinality is a collection
     */
    public static Reduction defaultFor(boolean holdsManyValues) {
        return holdsManyValues ? UNION_DISTINCT : REQUIRE_AGREEMENT;
    }

    /** What a modeller reads. A combo showing UNION_DISTINCT is showing them the name
     *  of a constant, which is a different thing from what it does. */
    @Override
    public String toString() {
        return switch (this) {
            case REQUIRE_AGREEMENT -> "Must agree";
            case UNION_DISTINCT -> "Combine them";
            case PREFER_NON_EMPTY -> "Take whichever is present";
            case CHOOSE_BY_POLICY -> "Choose by a configured policy";
        };
    }
}
