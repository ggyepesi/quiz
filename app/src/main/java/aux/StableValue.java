package aux;

/**
 * A value type that publishes its own canonical, process-independent text.
 *
 * <p>Lineage identity hashes the value a claim proposes, so the text has to mean the
 * same thing in a later process — {@code toString()} does not promise that, and a
 * lineage built on it silently changes when a rendering does. A type that knows its
 * canonical form says so here rather than being recognised by name or by
 * {@code instanceof} somewhere else, which is what keeps the rule in one place while
 * the types stay in theirs.
 *
 * <p>Lives in {@code aux}, a leaf package, so a value type can implement it without
 * depending upward on the evidence layer that reads it — the same arrangement as
 * {@link objectview.utils.Addressable}.
 */
public interface StableValue {

    /** Canonical text for this value: equal values give equal text, in any process. */
    String stableForm();
}
