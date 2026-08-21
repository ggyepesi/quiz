package aux;

import objectview.utils.Addressable;

import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A date of flexible precision — year, year-month, or a full date — mirroring
 * Wikidata's time precision model. The string form is self-describing
 * ({@code "1959"}, {@code "1959-04"}, {@code "1959-04-06"}, {@code "500 BC"}),
 * so {@link #parse} round-trips {@link #format} without a separate precision
 * field. BC dates are held as a negative year (matching the sign of Wikidata's
 * time literal). Immutable, comparable chronologically.
 */
public class FlexibleDate implements Comparable<FlexibleDate>, Addressable, StableValue {

    public enum Precision { YEAR, MONTH, DAY }

    // month/day are 0 when the precision doesn't reach them; year may be
    // negative (BC).
    private final int year;
    private final int month;
    private final int day;

    // Constructor: year only
    public FlexibleDate(int year) {
        this.year = year;
        this.month = 0;
        this.day = 0;
    }

    // Constructor: year + month
    public FlexibleDate(int year, int month) {
        YearMonth.of(Math.abs(year), month);   // validate month
        this.year = year;
        this.month = month;
        this.day = 0;
    }

    // Constructor: year + month (String)
    public FlexibleDate(int year, String monthStr) {
        this(year, parseMonth(monthStr));
    }

    // Constructor: year + month + day
    public FlexibleDate(int year, int month, int day) {
        LocalDate.of(Math.abs(year), month, day);   // validate month/day
        this.year = year;
        this.month = month;
        this.day = day;
    }

    // Constructor: year + month (String) + day
    public FlexibleDate(int year, String monthStr, int day) {
        this(year, parseMonth(monthStr), day);
    }

    /**
     * Parses the self-describing string form: {@code "1959"}, {@code "1959-04"},
     * {@code "1959-04-06"}, with an optional {@code " BC"} suffix, or a raw
     * Wikidata time literal ({@code [+-]YYYY-MM-DDThh:mm:ssZ}). Returns null for
     * anything that isn't a date.
     */
    public static FlexibleDate parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        s = s.trim();

        FlexibleDate wd = fromWikidataLiteral(s);
        if (wd != null) {
            return wd;
        }

        boolean bc = false;
        if (s.toUpperCase(Locale.ENGLISH).endsWith(" BC")) {
            bc = true;
            s = s.substring(0, s.length() - 3).trim();
        }

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(\\d{1,9})(?:-(\\d{1,2})(?:-(\\d{1,2}))?)?$")
                .matcher(s);
        if (!m.matches()) {
            return null;
        }
        try {
            int y = Integer.parseInt(m.group(1));
            if (bc) {
                y = -y;
            }
            if (m.group(3) != null) {
                return new FlexibleDate(y,
                        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            }
            if (m.group(2) != null) {
                return new FlexibleDate(y, Integer.parseInt(m.group(2)));
            }
            return new FlexibleDate(y);
        } catch (RuntimeException invalid) {
            return null;   // e.g. month 13 — not a date after all
        }
    }

    // A Wikidata time value ([+-]YYYY-MM-DDThh:mm:ssZ) carries its precision in a
    // separate field, so the literal alone has to be read for it. The API says what it
    // means — an unspecified month or day is 00 — while WDQS pads the same value to
    // -01-01. Both are read here: 00 first, because it is the source's own encoding,
    // then the padding convention, which cannot tell a year-precision date from a real
    // first of January.
    private static final java.util.regex.Pattern WD_TIME =
            java.util.regex.Pattern.compile("^([+-]?)0*(\\d+)-(\\d{2})-(\\d{2})T");

    /**
     * Converts a raw Wikidata time literal; null when {@code s} isn't one —
     * safe to probe arbitrary values with.
     */
    public static FlexibleDate fromWikidataLiteral(String s) {
        if (s == null) {
            return null;
        }
        java.util.regex.Matcher m = WD_TIME.matcher(s.trim());
        if (!m.find()) {
            return null;
        }
        try {
            int y = Integer.parseInt(m.group(2));
            if ("-".equals(m.group(1))) {
                y = -y;
            }
            int mm = Integer.parseInt(m.group(3));
            int dd = Integer.parseInt(m.group(4));
            if (mm == 0) {
                return new FlexibleDate(y);
            }
            if (dd == 0) {
                return new FlexibleDate(y, mm);
            }
            if (mm == 1 && dd == 1) {
                return new FlexibleDate(y);
            }
            if (dd == 1) {
                return new FlexibleDate(y, mm);
            }
            return new FlexibleDate(y, mm, dd);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    public Precision precision() {
        return day != 0 ? Precision.DAY
                : month != 0 ? Precision.MONTH
                : Precision.YEAR;
    }

    // The year, whatever precision this date was set at. Negative = BC.
    public int getYear() {
        return year;
    }

    /** 1-12, or 0 when the precision is YEAR. */
    public int getMonth() {
        return month;
    }

    /** 1-31, or 0 when the precision doesn't reach the day. */
    public int getDay() {
        return day;
    }

    /** The recurring calendar position (month + day, year-agnostic) — a birthday.
     *  Null unless this date reaches DAY precision. Comparable, so it groups/sorts. */
    public java.time.MonthDay monthDay() {
        if (precision() != Precision.DAY) {
            return null;
        }
        try {
            return java.time.MonthDay.of(month, day);
        } catch (RuntimeException e) {
            return null;   // an out-of-range month/day (bad source data)
        }
    }

    // --- Addressable: the type owns its view vocabulary, so a path resolver reads
    //     date.year / date.monthDay generically. A view is null below its precision
    //     (e.g. monthDay on a year-only date), so callers skip it rather than group
    //     on a spurious 0.
    private static final Set<String> VIEWS =
            Set.of("year", "month", "day", "monthDay");

    @Override
    public Object view(String name) {
        return switch (name) {
            case "year" -> year;
            case "month" -> precision().compareTo(Precision.MONTH) >= 0 ? month : null;
            case "day" -> precision() == Precision.DAY ? day : null;
            case "monthDay" -> monthDay();
            default -> null;
        };
    }

    @Override
    public Set<String> viewNames() {
        return VIEWS;
    }

    /** The canonical form is the one this date is persisted and compared by. */
    @Override public String stableForm() { return format(); }

    // Format method: "1959", "1959-04", "1959-04-06", "500 BC".
    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append(Math.abs(year));
        if (month != 0) {
            sb.append(month < 10 ? "-0" : "-").append(month);
            if (day != 0) {
                sb.append(day < 10 ? "-0" : "-").append(day);
            }
        }
        if (year < 0) {
            sb.append(" BC");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return format();
    }

    // Chronological; an unset month/day (0) sorts before any set one.
    @Override
    public int compareTo(FlexibleDate other) {
        int c = Integer.compare(year, other.year);
        if (c != 0) return c;
        c = Integer.compare(month, other.month);
        if (c != 0) return c;
        return Integer.compare(day, other.day);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FlexibleDate d
                && year == d.year && month == d.month && day == d.day;
    }

    @Override
    public int hashCode() {
        return Objects.hash(year, month, day);
    }

    // Helper: parse month from string
    private static int parseMonth(String monthStr) {
        monthStr = monthStr.trim();

        // Try numeric first
        try {
            int month = Integer.parseInt(monthStr);
            if (month >= 1 && month <= 12) return month;
        } catch (NumberFormatException ignored) {}

        // Try text (Jan, January, etc.)
        for (int i = 1; i <= 12; i++) {
            String shortName = Month.of(i)
                    .getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            String fullName = Month.of(i)
                    .getDisplayName(TextStyle.FULL, Locale.ENGLISH);

            if (monthStr.equalsIgnoreCase(shortName) ||
                monthStr.equalsIgnoreCase(fullName)) {
                return i;
            }
        }

        throw new IllegalArgumentException("Invalid month: " + monthStr);
    }
}
