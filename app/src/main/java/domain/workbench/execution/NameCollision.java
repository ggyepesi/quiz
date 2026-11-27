package domain.workbench.execution;

import objectview.Viewable;
import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.annotations.Hidden;
import objectview.annotations.Label;

import java.util.Collection;
import java.util.List;

/**
 * One ambiguous label as an ordinary ObjectView row. Its visible fields make the
 * collision list searchable and sortable; the hidden members are the actual generated
 * instances shown in the adjacent instance browser after selection.
 */
public final class NameCollision extends ViewableAdapter {

    private static final String TYPE = "NameCollision";
    @Hidden
    private final String identifier;
    @DisplayField
    @Label("Shared name")
    private final String name;
    @Label("Class")
    private final String className;
    @Label("Instances")
    private final int instances;
    @Hidden
    private final List<Viewable> entities;

    public NameCollision(String className, String name, Collection<? extends Viewable> entities) {
        this.className = text(className);
        this.name = text(name);
        this.identifier = key(this.className, this.name);
        this.entities = entities == null ? List.of() : List.copyOf(entities);
        this.instances = this.entities.size();
    }

    public String name() { return name; }
    public String className() { return className; }
    public int instances() { return instances; }
    public List<Viewable> entities() { return entities; }

    @Override public String getIdentifier() { return identifier; }
    @Override public String getDisplayName() { return name; }
    @Override public String typeName() { return TYPE; }

    private static String key(String className, String name) {
        return text(className) + '\0' + text(name);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
