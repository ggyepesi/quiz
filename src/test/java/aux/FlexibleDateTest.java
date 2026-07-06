package aux;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The string form is self-describing (precision included), so parse∘format is
 * the identity; Wikidata time literals collapse their conventional padding to
 * the implied precision; ordering is chronological across precisions and eras.
 */
class FlexibleDateTest {

    @Test void parseRoundTripsFormatAtEveryPrecision() {
        for (String s : new String[] {"1959", "1959-04", "1959-04-06", "500 BC"}) {
            FlexibleDate d = FlexibleDate.parse(s);
            assertNotNull(d, s);
            assertEquals(s, d.format());
            assertEquals(d, FlexibleDate.parse(d.format()));
        }
    }

    @Test void wikidataLiteralCollapsesPaddingToPrecision() {
        assertEquals("1875", FlexibleDate.fromWikidataLiteral(
                "1875-01-01T00:00:00Z").format());
        assertEquals("1959-04", FlexibleDate.fromWikidataLiteral(
                "+1959-04-01T00:00:00Z").format());
        assertEquals("1959-04-06", FlexibleDate.fromWikidataLiteral(
                "1959-04-06T00:00:00Z").format());
        assertEquals("5000 BC", FlexibleDate.fromWikidataLiteral(
                "-5000-01-01T00:00:00Z").format());
        // Not a time literal — must stay null so arbitrary values can be probed.
        assertNull(FlexibleDate.fromWikidataLiteral("Casablanca"));
        assertNull(FlexibleDate.fromWikidataLiteral("1959"));
    }

    @Test void nonDatesParseToNull() {
        assertNull(FlexibleDate.parse(null));
        assertNull(FlexibleDate.parse(""));
        assertNull(FlexibleDate.parse("Casablanca"));
        assertNull(FlexibleDate.parse("1959-13"));   // month 13
    }

    @Test void ordersChronologicallyAcrossPrecisionsAndEras() {
        FlexibleDate bc = FlexibleDate.parse("500 BC");
        FlexibleDate year = new FlexibleDate(1959);
        FlexibleDate month = new FlexibleDate(1959, 4);
        FlexibleDate day = new FlexibleDate(1959, 4, 6);
        FlexibleDate later = new FlexibleDate(1960);

        assertTrue(bc.compareTo(year) < 0);
        assertTrue(year.compareTo(month) < 0);   // unset month sorts first
        assertTrue(month.compareTo(day) < 0);
        assertTrue(day.compareTo(later) < 0);
    }

    @Test void precisionAndAccessors() {
        assertEquals(FlexibleDate.Precision.YEAR, new FlexibleDate(1959).precision());
        assertEquals(FlexibleDate.Precision.MONTH, new FlexibleDate(1959, 4).precision());
        assertEquals(FlexibleDate.Precision.DAY, new FlexibleDate(1959, 4, 6).precision());
        assertEquals(-500, FlexibleDate.parse("500 BC").getYear());
    }

    @Test void monthNameConstructorsStillWork() {
        assertEquals("2026-04", new FlexibleDate(2026, "Apr").format());
        assertEquals("2026-04-22", new FlexibleDate(2026, "April", 22).format());
    }
}
