package quiz.ordering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderValueTest {
    @Test
    void comparesFlexiblePrecisionDatesChronologically() {
        OrderValue year = OrderValue.parse(OrderValueType.DATE, "1959");
        OrderValue day = OrderValue.parse(OrderValueType.DATE, "1959-08-21");

        assertTrue(year.compareTo(day) < 0);
        assertEquals("1959-08-21", day.label());
    }

    @Test
    void comparesNumbersNumericallyRatherThanLexically() {
        OrderValue ten = OrderValue.parse(OrderValueType.NUMBER, "10");
        OrderValue two = OrderValue.parse(OrderValueType.NUMBER, "2");

        assertTrue(ten.compareTo(two) > 0);
    }

    @Test
    void incompatibleTypesFailFast() {
        assertThrows(IllegalArgumentException.class, () ->
                OrderValue.parse(OrderValueType.NUMBER, "ten"));
        assertThrows(IllegalArgumentException.class, () ->
                OrderValue.parse(OrderValueType.DATE, "unknown"));
    }
}
