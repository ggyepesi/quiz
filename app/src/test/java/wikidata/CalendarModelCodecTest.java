package wikidata;

import aux.FlexibleDate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The adapter between Wikidata's calendar vocabulary and a date that knows only
 * GREGORIAN and JULIAN. Both loading paths — the API reader and the SPARQL
 * extractor — come through here, so they cannot drift apart.
 */
class CalendarModelCodecTest {

    private static final String JULIAN = "http://www.wikidata.org/entity/Q1985786";
    private static final String GREGORIAN = "http://www.wikidata.org/entity/Q1985727";

    @Test void decodesTheTwoModelsWikibaseActuallyUses() {
        assertEquals(FlexibleDate.Calendar.JULIAN,
                CalendarModelCodec.decode(JULIAN).calendar());
        assertEquals(FlexibleDate.Calendar.GREGORIAN,
                CalendarModelCodec.decode(GREGORIAN).calendar());
    }

    @Test void anUnknownModelIsNotQuietlyGregorian() {
        CalendarModelCodec.Decoded decoded =
                CalendarModelCodec.decode("http://www.wikidata.org/entity/Q4242424");

        assertFalse(decoded.recognised(),
                "a model this codec does not know is a question, not a default");
        assertNull(decoded.calendar());
        assertEquals("http://www.wikidata.org/entity/Q4242424", decoded.sourceModel(),
                "the source identifier is preserved so a caller can keep or report it");
    }

    @Test void sayingNothingIsNotTheSameAsSayingSomethingUnknown() {
        // Absent means the source did not state a calendar, which is Wikidata's own
        // default; unrecognised means it stated one nobody here understands.
        assertTrue(CalendarModelCodec.decode(null).recognised());
        assertEquals(FlexibleDate.Calendar.GREGORIAN,
                CalendarModelCodec.decode("").calendar());
    }

    @Test void aSuffixOfTheItemIdIsNotTheItemId() {
        assertFalse(CalendarModelCodec.decode(
                "http://www.wikidata.org/entity/5786").recognised());
        assertFalse(CalendarModelCodec.decode("Q19857860").recognised());
    }

    @Test void readsCalendarAndPrecisionOutOfOneWireValue() {
        String wire = "+1500-03-01T00:00:00Z"
                + CalendarModelCodec.calendarMark(JULIAN)
                + CalendarModelCodec.precisionMark(11);

        FlexibleDate date = CalendarModelCodec.readTime(wire);

        assertEquals(FlexibleDate.Calendar.JULIAN, date.calendar());
        assertEquals(FlexibleDate.Precision.DAY, date.precision());
        assertEquals("1500-03-01 (Julian)", date.format(),
                "and it survives into the form a snapshot is written in");
    }

    @Test void statedPrecisionBeatsThePaddingConvention() {
        // WDQS pads a year to -01-01, which the numbers cannot be told from a real
        // 1 January. Only the stated precision separates them.
        String firstJanuary = "+1959-01-01T00:00:00Z";

        assertEquals(FlexibleDate.Precision.DAY, CalendarModelCodec.readTime(
                firstJanuary + CalendarModelCodec.precisionMark(11)).precision());
        assertEquals(FlexibleDate.Precision.YEAR, CalendarModelCodec.readTime(
                firstJanuary + CalendarModelCodec.precisionMark(9)).precision());
    }

    @Test void readingReportsAnUnknownModelRatherThanSwallowingIt() {
        List<String> reported = new ArrayList<>();
        FlexibleDate date = CalendarModelCodec.readTime(
                "+1500-03-01T00:00:00Z"
                        + CalendarModelCodec.calendarMark(
                                "http://www.wikidata.org/entity/Q4242424"),
                reported::add);

        assertEquals(List.of("http://www.wikidata.org/entity/Q4242424"), reported);
        assertEquals(FlexibleDate.Calendar.GREGORIAN, date.calendar(),
                "the value still reads, but the caller was told which model it was");
    }

    @Test void bothLoadingPathsBuildTheSameWireForm() {
        // The SPARQL side concatenates the same separator the API reader writes,
        // so one translator serves both rather than each remembering the format.
        assertTrue(CalendarModelCodec.calendarMarkExpression("born_0_c")
                        .contains("STR(?born_0_c)"));
        assertTrue(CalendarModelCodec.calendarMark(JULIAN).startsWith(
                CalendarModelCodec.calendarMarkExpression("x").substring(1, 2)),
                "the same separator on both sides");
    }
}
