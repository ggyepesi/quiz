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

    /**
     * The action API states an unspecified month or day as 00, where WDQS pads the same
     * value to -01-01. Reading only the padding convention made every 00 literal fail
     * LocalDate validation and fall back to its raw text: 2,571 films came out of one
     * generation with "+2015-00-00T00:00:00Z" sitting in a date field.
     */
    @Test void wikidataZeroMonthOrDayIsThePrecisionTheSourceStated() {
        assertNotNull(FlexibleDate.fromWikidataLiteral("+2015-00-00T00:00:00Z"),
                "a 00 month is a stated precision, not an invalid date");
        assertEquals("2015", FlexibleDate.fromWikidataLiteral(
                "+2015-00-00T00:00:00Z").format());
        assertEquals("2015-06", FlexibleDate.fromWikidataLiteral(
                "+2015-06-00T00:00:00Z").format());
        assertEquals(FlexibleDate.Precision.YEAR, FlexibleDate.fromWikidataLiteral(
                "+2015-00-00T00:00:00Z").precision());
        assertEquals(FlexibleDate.Precision.MONTH, FlexibleDate.fromWikidataLiteral(
                "+2015-06-00T00:00:00Z").precision());
        assertEquals("500 BC", FlexibleDate.fromWikidataLiteral(
                "-0500-00-00T00:00:00Z").format());
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

    // --- Calendar (0c) -------------------------------------------------
    // Wikidata states a calendar per time value. The same numbers mean a
    // different day in each, so the calendar has to survive every channel a
    // date travels in — including the String-only ones.


    @Test
    void aJulianDateSurvivesFormatAndParse() {
        // Not day 1 — the padding convention reads that as month precision.
        FlexibleDate julian = FlexibleDate.fromWikidataLiteral("+1500-03-15T00:00:00Z")
                .inCalendar(FlexibleDate.Calendar.JULIAN);
        FlexibleDate back = FlexibleDate.parse(julian.format());
        assertEquals(julian, back, "format/parse is the persistence contract");
        assertEquals(FlexibleDate.Calendar.JULIAN, back.calendar());
        assertEquals(1500, back.getYear());
        assertEquals(3, back.getMonth());
        assertEquals(15, back.getDay());
    }

    @Test
    void aGregorianDateWritesExactlyWhatItAlwaysDid() {
        // Nothing to migrate: every date written before calendars existed reads
        // back unchanged, and writes back byte-identical.
        assertEquals("1959-04-06", FlexibleDate.parse("1959-04-06").format());
        assertEquals("500 BC", FlexibleDate.parse("500 BC").format());
        assertEquals("2015", FlexibleDate.fromWikidataLiteral(
                "+2015-00-00T00:00:00Z").format());
    }




    @Test
    void sameNumbersInDifferentCalendarsAreDifferentDays() {
        FlexibleDate g = FlexibleDate.parse("1500-03-01");
        FlexibleDate j = g.inCalendar(FlexibleDate.Calendar.JULIAN);
        assertNotEquals(g, j);
        assertNotEquals(0, g.compareTo(j), "compareTo stays consistent with equals");
        assertEquals("JULIAN", j.view("calendar"));
    }

}
