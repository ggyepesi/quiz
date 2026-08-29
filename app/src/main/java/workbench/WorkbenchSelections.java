package workbench;

import wikidata.WikidataIds;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Window-scoped, typed copy/paste state for workbench values.
 *
 * <p>Card selection stays transient; only an explicit "Select" action changes these.
 * Each kind accumulates, because collecting is what a reader is doing when they search
 * for "Nobel Prize" and want the five categories: pick them one at a time, name them
 * once.
 *
 * <p>Arity is the using side's question, and it is answered by which accessor a tool
 * reads. {@link #entities()} is for a tool that acts on the whole collection;
 * {@link #entity()} is for one that needs a single starting point, and it is empty
 * unless exactly one is selected — a walk cannot begin at six places, and quietly
 * beginning at whichever was picked last would be an arbitrary answer to a question
 * with no single answer.
 */
public final class WorkbenchSelections {

    /** A listener registration whose lifetime is owned by the UI that requested it. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override void close();
    }

    public record Entity(String qid, String label) {
        public Entity {
            if (!WikidataIds.isQid(qid)) throw new IllegalArgumentException("Invalid QID");
            label = label == null || label.isBlank() ? qid : label;
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

    /** The one selected entity, when exactly one is. See the class note on arity. */
    public Optional<Entity> entity() {
        return entities.size() == 1
                ? Optional.of(entities.values().iterator().next()) : Optional.empty();
    }

    public Optional<Property> property() {
        return properties.size() == 1
                ? Optional.of(properties.values().iterator().next()) : Optional.empty();
    }

    /** Adds to the selection; picking the same value twice does not select it twice. */
    public void entity(String qid, String label) {
        Entity next = new Entity(qid, label);
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
