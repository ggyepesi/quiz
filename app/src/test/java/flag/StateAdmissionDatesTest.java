package flag;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StateAdmissionDatesTest {
    @Test
    void resourceContainsAllStatesAndPreservesTheSharedDakotaDate() {
        Map<String, aux.FlexibleDate> dates = StateAdmissionDates.load();

        assertEquals(50, dates.size());
        assertEquals("1787-12-07", dates.get("Delaware").format());
        assertEquals("1959-08-21", dates.get("Hawaii").format());
        assertEquals(dates.get("North Dakota"), dates.get("South Dakota"));
    }
}
