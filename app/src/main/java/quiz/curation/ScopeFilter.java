package quiz.curation;

/**
 * Which members of an explicit curation scope a task operates on — a scope filter, NOT a
 * value lifecycle. For a field task it means missing / present / all values; for an identity
 * task it means unresolved / resolved / all instances.
 */
public enum ScopeFilter {
    MISSING("Missing / unresolved"),
    PRESENT("Existing / resolved"),
    ALL("All");

    private final String label;

    ScopeFilter(String label) { this.label = label; }

    @Override public String toString() { return label; }
}
