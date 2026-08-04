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
                        ? new FieldTypeInfo("int", false, false, null, null,
                                null, objectview.field.FieldRole.NONE,
                                objectview.field.FieldKind.ORDERED,
                                objectview.field.FieldKind.ORDERED)
                        : null;
            }
            @Override public List<String> fieldNames() {
                return List.of("year");
            }
        };
        return new FieldTypeSource() {
            @Override public FieldTypeInfo field(String name) {
                return "category".equals(name)
                        ? new FieldTypeInfo("Category", false, false,
                                "Category", categoryChildren, null,
                                objectview.field.FieldRole.NONE,
                                objectview.field.FieldKind.REFERENCE,
                                objectview.field.FieldKind.REFERENCE)
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

    /** A flat schema over a dynamic sample: "population" ordinary, "isoCode" MINOR. */
    private static FieldTypeSource minorSchema() {
        return new FieldTypeSource() {
            @Override public FieldTypeInfo field(String name) {
                return switch (name) {
                    case "population" -> new FieldTypeInfo("Long", false, false,
                            null, null, null, objectview.field.FieldRole.NONE,
                            objectview.field.FieldKind.ORDERED,
                            objectview.field.FieldKind.ORDERED);
                    case "isoCode" -> new FieldTypeInfo("String", false, true,
                            null, null, null, objectview.field.FieldRole.NONE,
                            objectview.field.FieldKind.TEXT,
                            objectview.field.FieldKind.TEXT);
                    default -> null;
                };
            }
            @Override public List<String> fieldNames() {
                return List.of("population", "isoCode");
            }
        };
    }

    private static DynamicViewable stateWithMinor() {
        DynamicViewable state = new DynamicViewable("Q40", "Austria");
        state.type("State");
        state.put("population", 9_000_000L);
        state.put("isoCode", "AT");
        return state;
    }

    private static boolean hasPath(List<FieldRow> rows, String path) {
        return rows.stream().anyMatch(r -> path.equals(r.path()));
    }

    @Test void schemaMinorDynamicFieldIsHiddenFromTheTable() {
        FieldRowContext ctx = new FieldRowContext(
                ViewConfig.all(DynamicViewable.class), stateWithMinor(),
                false, false, Set.of(), minorSchema());

        List<FieldRow> rows = ConfigFieldRowSource.INSTANCE.rows(ctx);
        assertTrue(hasPath(rows, "population"), "ordinary field stays in the table");
        assertFalse(hasPath(rows, "isoCode"),
                "a schema-declared minor dynamic field must NOT render in the table"
                        + " — it is governed wholesale by 'All minor fields'");
        assertFalse(rows.stream().anyMatch(FieldRow::isMinorBlock),
                "no per-field minor block: minor dynamic fields are checkbox-governed only");
    }

    @Test void minorOnlyRowsDoNotInjectTheOrdinaryIdentityName() {
        FieldRowContext live = new FieldRowContext(
                ViewConfig.all(DynamicViewable.class), stateWithMinor(),
                true, false, Set.of(), minorSchema());
        List<FieldRow> liveRows = ConfigFieldRowSource.INSTANCE.rows(live);
        assertEquals(List.of("isoCode"),
                liveRows.stream().map(FieldRow::path).toList());

        FieldRowContext schemaOnly = new FieldRowContext(
                ViewConfig.all(DynamicViewable.class), null,
                true, false, Set.of(), minorSchema());
        List<FieldRow> schemaRows = ConfigFieldRowSource.INSTANCE.rows(schemaOnly);
        assertEquals(List.of("isoCode"),
                schemaRows.stream().map(FieldRow::path).toList());
    }

    @Test void hasMinorFieldsDetectsDynamicMinor() {
        assertTrue(ConfigFieldRowSource.INSTANCE.hasMinorFields(new FieldRowContext(
                        ViewConfig.all(DynamicViewable.class), stateWithMinor(),
                        false, false, Set.of(), minorSchema())),
                "hasMinorFields must see a schema-declared dynamic minor field");

        DynamicViewable plain = new DynamicViewable("Q1", "Plain");
        plain.type("State");
        plain.put("population", 1L);
        assertFalse(ConfigFieldRowSource.INSTANCE.hasMinorFields(new FieldRowContext(
                        ViewConfig.all(DynamicViewable.class), plain,
                        false, false, Set.of(), minorSchema())),
                "no minor field present -> no bar");
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
