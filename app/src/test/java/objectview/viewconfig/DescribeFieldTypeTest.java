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
        // Reflected path: no instance needed — enumeration uses config.getCls().
        List<FieldRow> rows = ConfigFieldRowSource.INSTANCE.rows(new FieldRowContext(
                ViewConfig.all(State.class), null, false, false, Set.of(), null));
        return rows.stream()
                .filter(r -> field.equals(r.path()))
                .map(FieldRow::typeLabel)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + field));
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
        assertEquals("Map<String, QuizableGroup>", label("groups"));
    }
}
