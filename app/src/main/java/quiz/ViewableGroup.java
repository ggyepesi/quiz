package quiz;

import objectview.Viewable;
import objectview.group.DefaultViewableGroup;

/**
 * A group of {@link Viewable} members. It is deliberately not itself a
 * {@link Viewable}: a group is structural view/facet data, not a domain instance.
 * Binds the self-typed {@link DefaultViewableGroup} to
 * {@code ViewableGroup}, so its child/fluent methods return {@code ViewableGroup} with
 * no manual covariant overrides.
 */
public class ViewableGroup
        extends DefaultViewableGroup<Viewable, ViewableGroup> {

    public ViewableGroup(String name) {
        super(name);
    }

    @Override
    protected ViewableGroup newChild(String name) {
        return new ViewableGroup(name);
    }
}
