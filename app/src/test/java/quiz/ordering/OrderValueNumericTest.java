package quiz.ordering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ordering/timeline quiz reads NUMBER values through the shared NumericValues parser,
 *  so scaled/ranged speaker-style strings order correctly instead of throwing. */
class OrderValueNumericTest {

    private static OrderValue num(String raw) {
        return OrderValue.of(OrderValueType.NUMBER, raw);
    }

    @Test void scalesMagnitudeSoOrderIsByRealValue() {
        // "300 million" < "1 billion" — would be false if compared as bare 300 vs 1.
        assertTrue(num("300 million").compareTo(num("1 billion")) < 0);
        assertTrue(num("5 million").compareTo(num("300 million")) < 0);
    }

    @Test void rangeOrdersByMidpointButKeepsItsLabel() {
        OrderValue range = num("8–13 million");
        assertEquals("8–13 million", range.label());              // original text shown
        assertTrue(range.compareTo(num("9 million")) > 0);        // midpoint 10.5M > 9M
        assertTrue(range.compareTo(num("12 million")) < 0);       // midpoint 10.5M < 12M
    }

    @Test void blankIsMissingNotInvalid() {
        assertNull(num(null));
        assertNull(num("   "));
    }

    @Test void unparseablePresentValueIsInvalid() {
        // A percent / prose has data but no orderable count: the generator counts it invalid.
        assertThrows(IllegalArgumentException.class, () -> num("80% of China"));
        assertThrows(IllegalArgumentException.class, () -> num("many"));
    }
}
