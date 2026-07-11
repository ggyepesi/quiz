package quiz.curation;

/**
 * A domain that carries a {@link ManualCuration} store, so the generic workbench can
 * offer a "Curate…" action only when the backing domain supports it — a capability
 * seam like {@code SchemaView}, without the UI knowing what's inside.
 */
public interface Curatable {

    /** The manual-curation store for this domain, or null if it isn't curatable. */
    ManualCuration curation();
}
