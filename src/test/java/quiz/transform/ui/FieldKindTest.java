package quiz.transform.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The value-shape classifiers the DomainModels use to populate DomainField.kind. */
class FieldKindTest {

    @Test void ofValueClassifiesRuntimeValues() {
        assertEquals(FieldKind.BOOLEAN, FieldKind.ofValue(true));
        assertEquals(FieldKind.ORDERED, FieldKind.ofValue(3));
        assertEquals(FieldKind.ORDERED, FieldKind.ofValue(2.5));
        assertEquals(FieldKind.TEXT, FieldKind.ofValue("hi"));
        assertEquals(FieldKind.COLLECTION, FieldKind.ofValue(List.of(1)));
        assertEquals(FieldKind.UNKNOWN, FieldKind.ofValue(null));
    }

    @Test void ofClassClassifiesDeclaredTypes() {
        assertEquals(FieldKind.ORDERED, FieldKind.ofClass(int.class));
        assertEquals(FieldKind.ORDERED, FieldKind.ofClass(Double.class));
        assertEquals(FieldKind.BOOLEAN, FieldKind.ofClass(boolean.class));
        assertEquals(FieldKind.BOOLEAN, FieldKind.ofClass(Boolean.class));
        assertEquals(FieldKind.TEXT, FieldKind.ofClass(String.class));
        assertEquals(FieldKind.COLLECTION, FieldKind.ofClass(List.class));
        assertEquals(FieldKind.UNKNOWN, FieldKind.ofClass(null));
    }

    @Test void ofTypeLabelClassifiesLabels() {
        assertEquals(FieldKind.ORDERED, FieldKind.ofTypeLabel("Integer"));
        assertEquals(FieldKind.ORDERED, FieldKind.ofTypeLabel("Double"));
        assertEquals(FieldKind.BOOLEAN, FieldKind.ofTypeLabel("Boolean"));
        assertEquals(FieldKind.TEXT, FieldKind.ofTypeLabel("String"));
        assertEquals(FieldKind.ORDERED, FieldKind.ofTypeLabel("FlexibleDate"));
        assertEquals(FieldKind.UNKNOWN, FieldKind.ofTypeLabel("Category"));   // a class ref, not scalar
        assertEquals(FieldKind.UNKNOWN, FieldKind.ofTypeLabel(null));
    }

    @Test void domainFieldDefaultsKindFromShape() {
        assertEquals(FieldKind.REFERENCE, new DomainField("N", "category", true, false).kind());
        assertEquals(FieldKind.COLLECTION, new DomainField("N", "tags", false, true).kind());
        assertEquals(FieldKind.UNKNOWN, new DomainField("N", "name", false, false).kind());
        assertEquals(FieldKind.ORDERED,
                new DomainField("N", "year", false, false, FieldKind.ORDERED).kind());
    }
}
