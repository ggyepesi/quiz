package objectview.viewconfig;

import org.junit.jupiter.api.Test;
import quiz.transform.DynamicViewable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Schema-backed enumeration in {@link ConfigFieldRowSource}: a reference whose current
 * value is EMPTY must still be expandable — its children come from the model schema
 * ({@link FieldTypeSource#fieldNames()}), not from a sample value. Previously such a
 * reference produced {@code nested == null} (no disclosure).
 */
class ConfigFieldRowSourceSchemaTest {

    /** A schema declaring nomination.category as a reference whose children are the
     *  single field "year" — with a working {@link FieldTypeSource#fieldNames()}. */
    private static FieldTypeSource schema() {
        FieldTypeSource categoryChildren = new FieldTypeSource() {
            @Override public FieldTypeInfo field(String name) {
                return "year".equals(name)
                        ? new FieldTypeInfo("int", false, null, null)
                        : null;
            }
            @Override public List<String> fieldNames() {
                return List.of("year");
            }
        };
        return new FieldTypeSource() {
            @Override public FieldTypeInfo field(String name) {
                return "category".equals(name)
                        ? new FieldTypeInfo("Category", false, "Category", categoryChildren)
                        : null;
            }
            @Override public List<String> fieldNames() {
                return List.of("category");
            }
        };
    }

    private static FieldRow row(List<FieldRow> rows, String path) {
        return rows.stream()
                .filter(r -> path.equals(r.path()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + path + " in " + rows));
    }

    @Test void emptyReferenceIsExpandableFromSchema() {
        DynamicViewable nomination = new DynamicViewable("N1", "A Nomination");
        nomination.type("Nomination");
        nomination.put("category", new ArrayList<>());   // present but EMPTY -> no child value

        FieldRowContext ctx = new FieldRowContext(
                ViewConfig.all(DynamicViewable.class), nomination,
                false, false, Set.of(), schema());

        FieldRow category = row(ConfigFieldRowSource.INSTANCE.rows(ctx), "category");
        assertNotNull(category.nested(),
                "a schema-backed reference with an empty value must still be expandable");

        // And drilling into it enumerates the schema children WITHOUT any sample.
        NestedFieldSource nested = category.nested();
        FieldRowContext childCtx = new FieldRowContext(
                ViewConfig.all(nested.type()), nested.sample(),
                false, false, Set.of(), nested.fieldTypes());
        List<FieldRow> childRows = ConfigFieldRowSource.INSTANCE.rows(childCtx);
        assertTrue(childRows.stream().anyMatch(r -> "year".equals(r.path())),
                "schema children must enumerate with no sample, was " + childRows);
    }
}
