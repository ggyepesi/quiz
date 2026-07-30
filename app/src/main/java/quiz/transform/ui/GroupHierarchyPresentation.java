package quiz.transform.ui;

import objectview.Viewable;
import objectview.ViewableAdapter;
import objectview.group.ViewableGroup;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Presentation-only wrapper for one or more explicitly declared group roots.
 */
final class GroupHierarchyPresentation {
    private GroupHierarchyPresentation() {}

    static ViewableGroup<?> rootOf(List<? extends Viewable> values, String label) {
        if (values == null || values.isEmpty()) {
            return null;
        }

        List<ViewableGroup<?>> roots = new java.util.ArrayList<>();
        for (Viewable value : values) {
            if (!(value instanceof ViewableGroup<?> group)) {
                return null;
            }
            roots.add(group);
        }
        if (roots.size() == 1) {
            return roots.get(0);
        }
        return new ForestRoot(label, roots);
    }

    private static final class ForestRoot extends ViewableAdapter
            implements ViewableGroup<Viewable> {
        private final String label;
        private final Map<String, ViewableGroup<?>> children;
        private final Map<String, Viewable> members;

        private ForestRoot(String label, List<ViewableGroup<?>> roots) {
            this.label = label == null || label.isBlank() ? "Groups" : label;
            Map<String, ViewableGroup<?>> childMap = new LinkedHashMap<>();
            Map<String, Viewable> memberMap = new LinkedHashMap<>();
            for (ViewableGroup<?> root : roots) {
                childMap.put(root.getIdentifier(), root);
                for (Viewable member : root.getMembers()) {
                    memberMap.putIfAbsent(member.getIdentifier(), member);
                }
            }
            children = Collections.unmodifiableMap(childMap);
            members = Collections.unmodifiableMap(memberMap);
        }

        @Override public String getIdentifier() { return label; }
        @Override public String getDisplayName() { return label; }
        @Override public Role getRole() { return Role.UNIVERSE; }
        @Override public Viewable getKeyRef() { return null; }
        @Override public ViewableGroup<Viewable> getChild(String name) {
            @SuppressWarnings("unchecked")
            ViewableGroup<Viewable> child =
                    (ViewableGroup<Viewable>) children.get(name);
            return child;
        }
        @Override public Collection<? extends ViewableGroup<Viewable>> getChildren() {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Collection<? extends ViewableGroup<Viewable>> result =
                    (Collection) children.values();
            return result;
        }
        @Override public Map<String, ? extends ViewableGroup<Viewable>> getChildrenMap() {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Map<String, ? extends ViewableGroup<Viewable>> result = (Map) children;
            return result;
        }
        @Override public Collection<Viewable> getMembers() { return members.values(); }
        @Override public Map<String, Viewable> getMemberMap() { return members; }
        @Override public ViewableGroup<Viewable> getParent() { return null; }
        @Override public String getFullName() { return label; }
        @Override public boolean contains(String memberName) {
            return members.containsKey(memberName);
        }
    }
}
