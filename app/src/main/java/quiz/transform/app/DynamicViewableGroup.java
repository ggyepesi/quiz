package quiz.transform.app;

import objectview.Viewable;
import objectview.field.FieldSet;
import objectview.group.MultiRootGroup;
import objectview.group.ViewableGroup;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only group view over an ordinary dynamic object graph.
 *
 * <p>This is a rendering adapter only: it does not copy, rebuild or reinterpret the
 * persisted graph. Parent, children and members are read through the same dynamic
 * fields used by every other loaded Viewable.
 */
public final class DynamicViewableGroup implements ViewableGroup<Viewable> {

    private final WikidataDynamicObject object;
    private final Map<WikidataDynamicObject, DynamicViewableGroup> cache;

    private DynamicViewableGroup(
            WikidataDynamicObject object,
            Map<WikidataDynamicObject, DynamicViewableGroup> cache) {
        this.object = object;
        this.cache = cache;
    }

    /** Adapts explicit persisted group roots. No graph discovery is performed. */
    public static ViewableGroup<?> adapt(WikidataDynamicObject root) {
        return root == null || !isGroup(root)
                ? null : wrap(root, new IdentityHashMap<>());
    }

    /** Adapts explicit persisted group roots. No graph discovery is performed. */
    public static ViewableGroup<?> rootsOf(
            Collection<WikidataDynamicObject> roots) {
        if (roots == null || roots.isEmpty()) {
            return null;
        }
        Map<WikidataDynamicObject, DynamicViewableGroup> cache =
                new IdentityHashMap<>();
        List<DynamicViewableGroup> adapted = roots.stream()
                .filter(DynamicViewableGroup::isGroup)
                .map(root -> wrap(root, cache))
                .toList();
        return MultiRootGroup.of(adapted, "All");
    }

    private static boolean isGroup(WikidataDynamicObject object) {
        if (object == null) return false;
        String type = object.typeName();
        return "ViewableGroup".equals(type)
                || "EditableGroup".equals(type)
                || "FacetGroup".equals(type)
                || "OperationGroup".equals(type);
    }

    private static DynamicViewableGroup wrap(
            WikidataDynamicObject object,
            Map<WikidataDynamicObject, DynamicViewableGroup> cache) {
        return cache.computeIfAbsent(
                object, value -> new DynamicViewableGroup(value, cache));
    }

    @Override
    public String getIdentifier() {
        return object.getIdentifier();
    }

    @Override
    public String getDisplayName() {
        return object.getDisplayName();
    }

    @Override
    public String getReferenceLabel() {
        return object.getReferenceLabel();
    }

    @Override
    public String typeName() {
        return object.typeName();
    }

    @Override
    public FieldSet fields() {
        return object.fields();
    }

    @Override
    public Role getRole() {
        Object persisted = object.get("role");
        if (persisted instanceof String text) {
            return Role.valueOf(text);
        }
        throw new IllegalStateException(
                "Persisted group has no role: " + object.getIdentifier());
    }

    @Override
    public Viewable getKeyRef() {
        Object value = object.get("keyRef");
        return value instanceof Viewable viewable ? viewable : null;
    }

    @Override
    public Collection<DynamicViewableGroup> getChildren() {
        return groups(object.get("children"));
    }

    @Override
    public Collection<Viewable> getMembers() {
        return viewables(object.get("members"));
    }

    @Override
    public DynamicViewableGroup getParent() {
        Object value = object.get("parent");
        return value instanceof WikidataDynamicObject parent && isGroup(parent)
                ? wrap(parent, cache) : null;
    }

    @Override
    public String getFullName() {
        return getReferenceLabel();
    }

    private Collection<DynamicViewableGroup> groups(Object value) {
        List<DynamicViewableGroup> result = new ArrayList<>();
        for (Object item : values(value)) {
            if (item instanceof WikidataDynamicObject group && isGroup(group)) {
                result.add(wrap(group, cache));
            }
        }
        return List.copyOf(result);
    }

    private static Collection<Viewable> viewables(Object value) {
        List<Viewable> result = new ArrayList<>();
        for (Object item : values(value)) {
            if (item instanceof Viewable viewable) {
                result.add(viewable);
            }
        }
        return List.copyOf(result);
    }

    private static Collection<?> values(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection;
        }
        if (value instanceof Map<?, ?> map) {
            return map.values();
        }
        return value == null ? List.of() : List.of(value);
    }
}
