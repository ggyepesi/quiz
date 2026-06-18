package aux;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

public class FlexibleDate {

    private Year year;
    private YearMonth yearMonth;
    private LocalDate fullDate;

    // Constructor: year only
    public FlexibleDate(int year) {
        this.year = Year.of(year);
    }

    // Constructor: year + month (String)
    public FlexibleDate(int year, String monthStr) {
        int month = parseMonth(monthStr);
        this.yearMonth = YearMonth.of(year, month);
    }

    // Constructor: year + month (String) + day
    public FlexibleDate(int year, String monthStr, int day) {
        int month = parseMonth(monthStr);
        this.fullDate = LocalDate.of(year, month, day);
    }

    // The year, whatever precision this date was set at.
    public int getYear() {
        if (fullDate != null) {
            return fullDate.getYear();
        } else if (yearMonth != null) {
            return yearMonth.getYear();
        } else if (year != null) {
            return year.getValue();
        } else {
            throw new IllegalStateException("No date value set");
        }
    }

    // Format method
    public String format() {
        if (fullDate != null) {
            return fullDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        } else if (yearMonth != null) {
            return yearMonth.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        } else if (year != null) {
            return year.format(DateTimeFormatter.ofPattern("yyyy"));
        } else {
            throw new IllegalStateException("No date value set");
        }
    }

    @Override
    public String toString() {
        return format();
    }

    // Helper: parse month from string
    private int parseMonth(String monthStr) {
        monthStr = monthStr.trim();

        // Try numeric first
        try {
            int month = Integer.parseInt(monthStr);
            if (month >= 1 && month <= 12) return month;
        } catch (NumberFormatException ignored) {}

        // Try text (Jan, January, etc.)
        for (int i = 1; i <= 12; i++) {
            String shortName = java.time.Month.of(i)
                    .getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            String fullName = java.time.Month.of(i)
                    .getDisplayName(TextStyle.FULL, Locale.ENGLISH);

            if (monthStr.equalsIgnoreCase(shortName) ||
                monthStr.equalsIgnoreCase(fullName)) {
                return i;
            }
        }

        throw new IllegalArgumentException("Invalid month: " + monthStr);
    }

    // Example usage
    public static void main(String[] args) {
        System.out.println(new FlexibleDate(2026));                // 2026
        System.out.println(new FlexibleDate(2026, "4"));           // 2026-04
        System.out.println(new FlexibleDate(2026, "Apr"));         // 2026-04
        System.out.println(new FlexibleDate(2026, "April", 22));   // 2026-04-22
    }
}