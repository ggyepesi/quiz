package workbench;

import wikidata.WikidataIds;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Window-scoped, typed copy/paste state for workbench values. Card selection remains
 * transient; only an explicit "Set selected" action changes these slots.
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

    private Entity entity;
    private Property property;
    private final List<Runnable> listeners = new ArrayList<>();

    public Optional<Entity> entity() { return Optional.ofNullable(entity); }
    public Optional<Property> property() { return Optional.ofNullable(property); }

    public void entity(String qid, String label) {
        entity = new Entity(qid, label);
        changed();
    }

    public void property(String pid, String label) {
        property = new Property(pid, label);
        changed();
    }

    public void clearEntity() { entity = null; changed(); }
    public void clearProperty() { property = null; changed(); }

    public void onChange(Runnable listener) {
        if (listener != null) listeners.add(listener);
    }

    private void changed() { List.copyOf(listeners).forEach(Runnable::run); }
}
