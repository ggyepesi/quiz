package work;

/** Receives workflow log updates. */
public interface LogListener {
    /**
     * A workflow log changed. {@code added} distinguishes the first notification for a
     * root from every later one, so a view can register the workflow once and upsert
     * thereafter. {@code terminalUpdate} says the change completed a node — possibly a
     * descendant, while the root itself keeps running — which a view may need to render
     * even when it would otherwise skip an ordinary streamed append.
     *
     * <p>This is the only method: an update that completes something is not a separate
     * kind of event, and a listener that could implement a shorter form would silently
     * stop distinguishing completion the moment it did.
     */
    void logChanged(LogNode root, boolean added, boolean terminalUpdate);
}
