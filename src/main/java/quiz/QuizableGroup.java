package quiz;

import objectview.DefaultViewableGroup;

import java.util.HashMap;
import java.util.List;

/**
 * A group of {@link Quizable} members that is itself a {@link Quizable} (so it renders
 * as its own card). Binds the self-typed {@link DefaultViewableGroup} to
 * {@code QuizableGroup}, so its child/fluent methods return {@code QuizableGroup} with
 * no manual covariant overrides. Projection/combination don't apply to a container.
 */
public class QuizableGroup
        extends DefaultViewableGroup<Quizable, QuizableGroup>
        implements Quizable {

    public QuizableGroup(String name) {
        super(name);
    }

    @Override
    protected QuizableGroup newChild(String name) {
        return new QuizableGroup(name);
    }

    @Override
    public HashMap<List<Object>, Quizable> generateUniqueCombinations(
            List<String> fieldNames) {
        throw new UnsupportedOperationException(
                "QuizableGroup does not support quiz projection");
    }

    @Override
    public Quizable project(
            List<String> fieldNames,
            List<Object> fieldValues) {
        throw new UnsupportedOperationException(
                "QuizableGroup does not support quiz projection");
    }
}
