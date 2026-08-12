package quiz.curation;

/**
 * Which members of an explicit curation scope a task operates on — a scope filter, NOT a
 * value lifecycle. For a field task it means missing / present / all values; for an identity
 * task it means unresolved / resolved / all instances.
 */
public enum ScopeFilter {
    MISSING("Missing / unresolved"),
    PRESENT("Existing / resolved"),
    ALL("All"),

    /**
     * The value is there, but what it points at has no name — a reference still showing
     * its own identifier. A gap the other three cannot express: such a member counts as
     * PRESENT, so it never appears in a worklist even though the field reads as a bare
     * QID everywhere it is rendered.
     *
     * <p>Field tasks only. An identity is not a reference, so this scope is not offered
     * for one; see {@code ValidationPanel.scopeChoices}.
     */
    UNNAMED_REFERENCE("Present, but the reference has no name"),

    /**
     * Empty because the SOURCE said so — "value unknown" or "no value" — rather than
     * because nobody has looked. Not a gap: no fetch will ever fill it, so it is kept
     * out of MISSING and listed on its own instead of sitting in the worklist forever.
     */
    ASSERTED_EMPTY("Empty — the source says unknown / none"),

    /**
     * The model declares one value, the instance holds several.
     *
     * <p>Not damage: nothing failed to fetch. It is the declaration and the data
     * disagreeing, so it is fixed by correcting the model and regenerating — repairing
     * the instances would paper over a rule that keeps producing it. Kept out of every
     * other scope because such a member is PRESENT and perfectly ordinary until you ask
     * how many values it has.
     */
    OVERFILLED_SINGLE("Single-valued, but holds several");

    private final String label;

    ScopeFilter(String label) { this.label = label; }

    @Override public String toString() { return label; }
}
