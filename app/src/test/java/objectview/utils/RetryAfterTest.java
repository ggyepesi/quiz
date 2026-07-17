package objectview.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetryAfterTest {

    @Test
    void deltaSecondsGetsAOneSecondCushion() {
        assertEquals(6000L, RetryAfter.millis("5", 1000L));
        assertEquals(1000L, RetryAfter.millis("0", 999L));
    }

    @Test
    void surroundingWhitespaceIsTolerated() {
        assertEquals(11000L, RetryAfter.millis("  10 ", -1L));
    }

    @Test
    void absentHeaderFallsBackToTheDefault() {
        assertEquals(1234L, RetryAfter.millis(null, 1234L));
    }

    @Test
    void httpDateOrGarbageFallsBackToTheDefault() {
        // Only the delta-seconds form is parsed; a date string is treated as absent.
        assertEquals(500L, RetryAfter.millis("Wed, 21 Oct 2015 07:28:00 GMT", 500L));
        assertEquals(42L, RetryAfter.millis("soon", 42L));
    }
}
