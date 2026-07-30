package quiz;

import objectview.Viewable;
import objectview.group.DefaultViewableGroup;

/**
 * A specialized {@link Viewable} containing a hierarchy and members. Group-specific
 * rendering is a presentation concern; fields, references, persistence and cycles use
 * the same object model as every other Viewable.
 *
 * <p>Binds the self-typed {@link DefaultViewableGroup} to
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
