package objectview.viewconfig;

import flag.State;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The reflected type label ({@code describeFieldType}) now surfaces generic arguments
 * regardless of Viewable-ness — a {@code String} element is as informative as a
 * {@code String} field — and shows the Map KEY type, not just the value. Verified over
 * the real {@link State} fields that motivated the change.
 */
class DescribeFieldTypeTest {

    private static String label(String field) {
        return labelOn(State.class, field);
    }

    private static String labelOn(Class<? extends objectview.Viewable> cls, String field) {
        // Reflected path: no instance needed — enumeration uses config.getCls().
        List<FieldRow> rows = ConfigFieldRowSource.INSTANCE.rows(new FieldRowContext(
                ViewConfig.all(cls), null, false, false, Set.of(), null));
        return rows.stream()
                .filter(r -> field.equals(r.path().dotted()))
                .map(FieldRow::typeLabel)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + field + " in " + rows));
    }

    @Test void nonViewableCollectionElementsAreShown() {
        assertEquals("Collection<String>", label("currencies"));
        assertEquals("Collection<String>", label("capitals"));
        assertEquals("Collection<ImagePane>", label("shapeVersions"));
        assertEquals("Collection<ImagePane>", label("armsVersions"));
    }

    @Test void viewableCollectionElementsUnchanged() {
        assertEquals("Collection<Language>", label("languages"));
    }

    @Test void mapShowsKeyAndValueTypes() {
        assertEquals("Map<String, ViewableGroup>", label("groups"));
    }

    // ViewableGroup extends DefaultViewableGroup<Viewable, ViewableGroup>; its inherited
    // children=Map<String,G> / members=Map<String,T> must resolve the type variables to
    // the real bound types, not show bare G / T.
    @Test void inheritedGenericFieldsResolveTypeVariables() {
        assertEquals("Map<String, ViewableGroup>",
                labelOn(quiz.ViewableGroup.class, "children"));
        assertEquals("Map<String, Viewable>",
                labelOn(quiz.ViewableGroup.class, "members"));
    }
}
