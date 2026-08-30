package workbench;

import wikidata.WikidataIds;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Window-scoped, typed copy/paste state for workbench values.
 *
 * <p>Card selection stays transient; only an explicit "Select" action changes these.
 * Each kind accumulates, because collecting is what a reader is doing when they search
 * for "Nobel Prize" and want the five categories: pick them one at a time, name them
 * once.
 *
 * <p>This object owns the shared collection, not the current UI selection within it.
 * Each realization of the reusable-selection dialog decides whether its list permits
 * one or several highlighted values and passes exactly those values to its consumer.
 */
public final class WorkbenchSelections {

    /** A listener registration whose lifetime is owned by the UI that requested it. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override void close();
    }

    /**
     * The presentation carried with an entity wherever it is shown in the workbench.
     * Identity remains the QID; label and description may improve as richer sources
     * encounter the same entity.
     */
    public record Entity(String qid, String label, String description) {
        public Entity(String qid, String label) { this(qid, label, ""); }
        public Entity {
            if (!WikidataIds.isQid(qid)) throw new IllegalArgumentException("Invalid QID");
            label = label == null || label.isBlank() ? qid : label;
            description = description == null ? "" : description;
        }
    }

    public record Property(String pid, String label) {
        public Property {
            if (!WikidataIds.isPid(pid)) throw new IllegalArgumentException("Invalid PID");
            label = label == null || label.isBlank() ? pid : label;
        }
    }

    // Source identity, not its currently-known label, decides whether a value is the
    // same reusable selection. LinkedHashMap preserves the reader's picking order while
    // allowing a later source to improve the label without duplicating the QID/PID.
    private final Map<String, Entity> entities = new LinkedHashMap<>();
    private final Map<String, Property> properties = new LinkedHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();

    /** Everything selected, in the order it was picked. */
    public List<Entity> entities() { return List.copyOf(entities.values()); }

    public List<Property> properties() { return List.copyOf(properties.values()); }

    /** Adds to the selection; picking the same value twice does not select it twice. */
    public void entity(String qid, String label) {
        entity(qid, label, "");
    }

    public void entity(String qid, String label, String description) {
        Entity next = new Entity(qid, label, description);
        Entity known = entities.get(qid);
        if (known != null) {
            next = new Entity(qid,
                    next.label().equals(qid) ? known.label() : next.label(),
                    next.description().isBlank() ? known.description() : next.description());
        }
        if (!next.equals(entities.put(next.qid(), next))) changed();
    }

    public void property(String pid, String label) {
        Property next = new Property(pid, label);
        if (!next.equals(properties.put(next.pid(), next))) changed();
    }

    public void removeEntity(Entity value) {
        if (value != null && entities.remove(value.qid()) != null) changed();
    }

    public void removeProperty(Property value) {
        if (value != null && properties.remove(value.pid()) != null) changed();
    }

    public void clearEntity() {
        if (!entities.isEmpty()) { entities.clear(); changed(); }
    }

    public void clearProperty() {
        if (!properties.isEmpty()) { properties.clear(); changed(); }
    }

    public Registration onChange(Runnable listener) {
        if (listener == null) return () -> {};
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void changed() { List.copyOf(listeners).forEach(Runnable::run); }
}
