package workbench;

import wikidata.WikidataIds;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    private final Set<Entity> entities = new LinkedHashSet<>();
    private final Set<Property> properties = new LinkedHashSet<>();
    private final List<Runnable> listeners = new ArrayList<>();

    /** Everything selected, in the order it was picked. */
    public List<Entity> entities() { return List.copyOf(entities); }

    public List<Property> properties() { return List.copyOf(properties); }

    /** The one selected entity, when exactly one is. See the class note on arity. */
    public Optional<Entity> entity() {
        return entities.size() == 1 ? Optional.of(entities.iterator().next()) : Optional.empty();
    }

    public Optional<Property> property() {
        return properties.size() == 1 ? Optional.of(properties.iterator().next()) : Optional.empty();
    }

    /** Adds to the selection; picking the same value twice does not select it twice. */
    public void entity(String qid, String label) {
        entities.add(new Entity(qid, label));
        changed();
    }

    public void property(String pid, String label) {
        properties.add(new Property(pid, label));
        changed();
    }

    public void removeEntity(Entity value) {
        if (entities.remove(value)) changed();
    }

    public void removeProperty(Property value) {
        if (properties.remove(value)) changed();
    }

    public void clearEntity() { entities.clear(); changed(); }

    public void clearProperty() { properties.clear(); changed(); }

    public void onChange(Runnable listener) {
        if (listener != null) listeners.add(listener);
    }

    private void changed() { List.copyOf(listeners).forEach(Runnable::run); }
}
