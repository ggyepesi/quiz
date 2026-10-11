package wikidata.explore.workbench;

import objectview.Viewable;
import objectview.group.ViewableGroupAdapter;

import java.util.Collection;
import java.util.List;

/**
 * One ambiguous label as an ordinary ObjectView group whose members are the actual
 * generated instances carrying that label. The group tree supplies the collision name
 * and cardinality; selecting it shows those instances with the same card renderer used
 * everywhere else.
 */
final class NameCollision extends ViewableGroupAdapter {

    private static final String TYPE = "NameCollision";
    private final String key;

    NameCollision(String className, String name, Collection<? extends Viewable> entities) {
        super(key(className, name), label(className, name));
        this.key = key(className, name);
        if (entities != null) entities.forEach(this::putMember);
    }

    private NameCollision(String identifier, String displayName) {
        super(identifier, displayName);
        this.key = identifier;
    }

    static NameCollision root(List<NameCollision> collisions) {
        NameCollision root = new NameCollision("name-collisions", "Name collisions");
        if (collisions != null) collisions.forEach(root::addCollision);
        return root;
    }

    private void addCollision(NameCollision collision) {
        if (collision == null) return;
        collision.parent(this);
        putChild(collision);
    }

    @Override public String groupKey() { return key; }

    @Override public String typeName() { return TYPE; }

    private static String key(String className, String name) {
        return text(className) + '\0' + text(name);
    }

    private static String label(String className, String name) {
        String type = text(className);
        String label = text(name);
        return type.isBlank() ? label : label + " — " + type;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
