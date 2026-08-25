package wikidata;

import aux.FlexibleDate;

/**
 * Translates Wikidata's calendar-model identifiers into the neutral calendar a
 * {@link FlexibleDate} understands, and builds the wire form both loading paths use.
 *
 * <p>{@link FlexibleDate} is a date, not a Wikidata date: it knows GREGORIAN and
 * JULIAN and nothing about item ids. This codec is the only place that knows
 * Q1985727 is one and Q1985786 is the other, so the SPARQL extractor and the API
 * reader agree by construction rather than by both remembering the same fact.
 *
 * <p>A model this codec does not recognise is NOT quietly read as Gregorian. The
 * source identifier is preserved on the {@link Decoded} result so a caller can keep
 * it or report it; treating an unknown calendar as the common one would misdate
 * precisely the values that are unusual enough to say so.
 */
public final class CalendarModelCodec {

    private static final String PROLEPTIC_GREGORIAN = "Q1985727";
    private static final String PROLEPTIC_JULIAN = "Q1985786";

    /** Separates a time literal from the calendar model stated beside it. */
    private static final String CALENDAR_SEPARATOR = "|";

    private CalendarModelCodec() {
    }

    /**
     * A decoded calendar model.
     *
     * @param calendar   the neutral calendar, or null when the model is unrecognised
     * @param sourceModel the identifier as the source gave it, kept when unrecognised
     */
    public record Decoded(FlexibleDate.Calendar calendar, String sourceModel) {

        public boolean recognised() {
            return calendar != null;
        }

        /** The calendar to read a value in, defaulting only where nothing was said. */
        public FlexibleDate.Calendar orGregorian() {
            return calendar == null ? FlexibleDate.Calendar.GREGORIAN : calendar;
        }
    }

    /** Absent or blank means the source said nothing, which is Gregorian —
     *  Wikidata's own default — and is not the same as saying something unknown. */
    public static Decoded decode(String calendarModelUri) {
        if (calendarModelUri == null || calendarModelUri.isBlank()) {
            return new Decoded(FlexibleDate.Calendar.GREGORIAN, "");
        }
        String uri = calendarModelUri.trim();
        String item = uri.substring(uri.lastIndexOf('/') + 1);
        if (PROLEPTIC_GREGORIAN.equals(item)) {
            return new Decoded(FlexibleDate.Calendar.GREGORIAN, uri);
        }
        if (PROLEPTIC_JULIAN.equals(item)) {
            return new Decoded(FlexibleDate.Calendar.JULIAN, uri);
        }
        return new Decoded(null, uri);
    }

    /** The suffix a time literal carries so the model survives a String-only channel.
     *  The model travels as the source stated it; nothing interprets it in transit. */
    public static String calendarMark(String calendarModelUri) {
        return calendarModelUri == null || calendarModelUri.isBlank()
                ? "" : CALENDAR_SEPARATOR + calendarModelUri.trim();
    }

    /**
     * The suffix carrying the precision Wikibase stated for a time value. The raw
     * literal alone is ambiguous: a genuine 1 January has the same numbers as a
     * year-precision value padded to {@code -01-01}.
     */
    public static String precisionMark(int wikidataPrecision) {
        return wikidataPrecision < 0 ? "" : " [precision=" + wikidataPrecision + "]";
    }

    /** The SPARQL expression that appends {@link #calendarMark} to a bound time. */
    public static String calendarMarkExpression(String calendarModelVar) {
        return "\"" + CALENDAR_SEPARATOR + "\", STR(?" + calendarModelVar + ")";
    }

    /**
     * Reads a wire-form time value: the literal, optionally followed by the calendar
     * model and the precision the source stated. Null when it is not a time at all.
     */
    public static FlexibleDate readTime(String wireValue) {
        return readTime(wireValue, null);
    }

    /**
     * As {@link #readTime(String)}, reporting a calendar model this codec does not
     * recognise rather than letting it pass as Gregorian.
     */
    public static FlexibleDate readTime(
            String wireValue, java.util.function.Consumer<String> unrecognised) {

        if (wireValue == null) {
            return null;
        }
        String s = wireValue.trim();

        // Precision last, as the wire form writes it.
        FlexibleDate.Precision stated = null;
        java.util.regex.Matcher precision = PRECISION_MARK.matcher(s);
        if (precision.find()) {
            stated = precisionOf(precision.group(1));
            s = s.substring(0, precision.start()).trim();
        }

        Decoded model = new Decoded(FlexibleDate.Calendar.GREGORIAN, "");
        int separator = s.lastIndexOf(CALENDAR_SEPARATOR);
        if (separator >= 0) {
            model = decode(s.substring(separator + 1));
            s = s.substring(0, separator).trim();
            if (!model.recognised() && unrecognised != null) {
                unrecognised.accept(model.sourceModel());
            }
        }
        FlexibleDate date = FlexibleDate.fromWikidataLiteral(s, stated);
        return date == null ? null : date.inCalendar(model.orGregorian());
    }

    private static final java.util.regex.Pattern PRECISION_MARK =
            java.util.regex.Pattern.compile("\\s*\\[precision=(\\d+)]$");

    /** Wikibase counts precision: 9 = year, 10 = month, 11 = day. Anything coarser
     *  stays year-shaped rather than inventing a month this type would have to show. */
    private static FlexibleDate.Precision precisionOf(String wikibasePrecision) {
        int p;
        try {
            p = Integer.parseInt(wikibasePrecision);
        } catch (NumberFormatException notANumber) {
            return null;
        }
        if (p <= 9) return FlexibleDate.Precision.YEAR;
        if (p == 10) return FlexibleDate.Precision.MONTH;
        return FlexibleDate.Precision.DAY;
    }
}
