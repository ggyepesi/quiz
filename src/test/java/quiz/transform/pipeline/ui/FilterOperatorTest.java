package quiz.transform.pipeline.ui;

import org.junit.jupiter.api.Test;
import quiz.transform.ui.FieldKind;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Operator applicability drives which operators the filter UI offers per field. */
class FilterOperatorTest {

    @Test void numericOperatorsFitOrderedFieldsOnly() {
        assertTrue(FilterOperator.BETWEEN.appliesTo(FieldKind.ORDERED));
        assertTrue(FilterOperator.LESS_THAN.appliesTo(FieldKind.ORDERED));
        assertFalse(FilterOperator.BETWEEN.appliesTo(FieldKind.TEXT));
        assertFalse(FilterOperator.LESS_THAN.appliesTo(FieldKind.BOOLEAN));
    }

    @Test void textOperatorsFitTextNotBoolean() {
        assertTrue(FilterOperator.STARTS_WITH.appliesTo(FieldKind.TEXT));
        assertTrue(FilterOperator.CONTAINS.appliesTo(FieldKind.COLLECTION));
        assertFalse(FilterOperator.STARTS_WITH.appliesTo(FieldKind.ORDERED));
        assertFalse(FilterOperator.CONTAINS.appliesTo(FieldKind.BOOLEAN));
    }

    @Test void sizeOperatorsFitCollectionsOnly() {
        assertTrue(FilterOperator.SIZE_GREATER_THAN.appliesTo(FieldKind.COLLECTION));
        assertTrue(FilterOperator.SIZE_EQUALS.appliesTo(FieldKind.COLLECTION));
        assertFalse(FilterOperator.SIZE_GREATER_THAN.appliesTo(FieldKind.TEXT));
        assertFalse(FilterOperator.SIZE_LESS_THAN.appliesTo(FieldKind.ORDERED));
        assertFalse(FilterOperator.SIZE_EQUALS.isUnary());
        assertFalse(FilterOperator.SIZE_EQUALS.isBinary());
    }

    @Test void booleanOperatorsFitBooleanOnly() {
        assertTrue(FilterOperator.IS_TRUE.appliesTo(FieldKind.BOOLEAN));
        assertFalse(FilterOperator.IS_TRUE.appliesTo(FieldKind.TEXT));
    }

    @Test void isEmptyFitsEveryShapeAndUnknownAllowsAll() {
        for (FieldKind k : FieldKind.values()) {
            assertTrue(FilterOperator.IS_EMPTY.appliesTo(k), "is-empty fits " + k);
        }
        for (FilterOperator op : FilterOperator.values()) {
            assertTrue(op.appliesTo(FieldKind.UNKNOWN), op + " allowed for UNKNOWN");
            assertTrue(op.appliesTo(null), op + " allowed for null");
        }
    }

    @Test void unaryAndBinaryClassification() {
        assertTrue(FilterOperator.IS_TRUE.isUnary());
        assertTrue(FilterOperator.IS_EMPTY.isUnary());
        assertFalse(FilterOperator.EQUALS.isUnary());
        assertTrue(FilterOperator.BETWEEN.isBinary());
        assertFalse(FilterOperator.EQUALS.isBinary());
    }
}
