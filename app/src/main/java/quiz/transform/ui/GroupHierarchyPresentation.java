package quiz.transform.ui;

import objectview.Viewable;
import objectview.group.MultiRootGroup;
import objectview.group.ViewableGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * Presentation-only wrapper for explicitly declared group roots: turns the pipeline's
 * selected {@code Viewable}s into a single {@link ViewableGroup} to render, or {@code null}
 * when they are not a pure group selection. The forest-of-roots shape lives in
 * {@link MultiRootGroup}.
 */
final class GroupHierarchyPresentation {
    private GroupHierarchyPresentation() {}

    static ViewableGroup<?> rootOf(List<? extends Viewable> values, String label) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<ViewableGroup<?>> roots = new ArrayList<>();
        for (Viewable value : values) {
            if (!(value instanceof ViewableGroup<?> group)) {
                return null;
            }
            roots.add(group);
        }
        return MultiRootGroup.of(roots, label);
    }
}
