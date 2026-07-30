package quiz.transform.app;

import objectview.Viewable;
import objectview.field.FieldSet;
import objectview.group.ViewableGroup;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        if (adapted.isEmpty()) {
            return null;
        }
        return adapted.size() == 1
                ? adapted.get(0) : new MultipleRootsGroup(adapted);
    }

    private static boolean isGroup(WikidataDynamicObject object) {
        return object != null && "ViewableGroup".equals(object.typeName());
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
        if (persisted instanceof Role role) {
            return role;
        }
        if (persisted instanceof String text) {
            try {
                return Role.valueOf(text);
            } catch (IllegalArgumentException ignored) {
                // Pre-v5 groups had no persisted role; use their shape below.
            }
        }
        if (getParent() == null) {
            return Role.UNIVERSE;
        }
        return getChildren().isEmpty() ? Role.BUCKET : Role.FACET;
    }

    @Override
    public Viewable getKeyRef() {
        Object value = object.get("keyRef");
        return value instanceof Viewable viewable ? viewable : null;
    }

    @Override
    public DynamicViewableGroup getChild(String name) {
        if (name == null) {
            return null;
        }
        for (DynamicViewableGroup child : getChildren()) {
            if (name.equals(child.getDisplayName())) {
                return child;
            }
        }
        return null;
    }

    @Override
    public Collection<DynamicViewableGroup> getChildren() {
        return groups(object.get("children"));
    }

    @Override
    public Map<String, DynamicViewableGroup> getChildrenMap() {
        Map<String, DynamicViewableGroup> result = new LinkedHashMap<>();
        for (DynamicViewableGroup child : getChildren()) {
            result.putIfAbsent(child.getDisplayName(), child);
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public Collection<Viewable> getMembers() {
        return viewables(object.get("members"));
    }

    @Override
    public Map<String, Viewable> getMemberMap() {
        Map<String, Viewable> result = new LinkedHashMap<>();
        for (Viewable member : getMembers()) {
            result.putIfAbsent(member.getIdentifier(), member);
        }
        return Collections.unmodifiableMap(result);
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

    @Override
    public boolean contains(String memberName) {
        return getMemberMap().containsKey(memberName);
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

    /** Synthetic universe used only when one snapshot contains independent group roots. */
    private static final class MultipleRootsGroup
            implements ViewableGroup<Viewable> {
        private final List<DynamicViewableGroup> roots;

        private MultipleRootsGroup(List<DynamicViewableGroup> roots) {
            this.roots = List.copyOf(roots);
        }

        @Override public String getIdentifier() { return "All"; }
        @Override public String getDisplayName() { return "All"; }
        @Override public FieldSet fields() {
            return new FieldSet() {
                @Override public Object read(String name) {
                    return "children".equals(name) ? roots : null;
                }
                @Override public boolean has(String name) {
                    return "children".equals(name);
                }
                @Override public void write(String name, Object value) {
                    throw new UnsupportedOperationException("read-only group");
                }
                @Override public List<objectview.field.FieldRef> fields() {
                    return List.of();
                }
            };
        }
        @Override public Role getRole() { return Role.UNIVERSE; }
        @Override public Viewable getKeyRef() { return null; }
        @Override public ViewableGroup<Viewable> getChild(String name) {
            return roots.stream()
                    .filter(root -> name != null
                            && name.equals(root.getDisplayName()))
                    .findFirst().orElse(null);
        }
        @Override public Collection<DynamicViewableGroup> getChildren() {
            return roots;
        }
        @Override public Map<String, DynamicViewableGroup> getChildrenMap() {
            Map<String, DynamicViewableGroup> result = new LinkedHashMap<>();
            for (DynamicViewableGroup root : roots) {
                result.putIfAbsent(root.getDisplayName(), root);
            }
            return Collections.unmodifiableMap(result);
        }
        @Override public Collection<Viewable> getMembers() {
            Set<Viewable> result =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            for (DynamicViewableGroup root : roots) {
                result.addAll(root.getMembers());
            }
            return List.copyOf(result);
        }
        @Override public Map<String, Viewable> getMemberMap() {
            Map<String, Viewable> result = new LinkedHashMap<>();
            for (Viewable member : getMembers()) {
                result.putIfAbsent(member.getIdentifier(), member);
            }
            return Collections.unmodifiableMap(result);
        }
        @Override public ViewableGroup<Viewable> getParent() { return null; }
        @Override public String getFullName() { return getDisplayName(); }
        @Override public boolean contains(String memberName) {
            return getMemberMap().containsKey(memberName);
        }
    }
}
