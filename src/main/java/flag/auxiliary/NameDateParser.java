package flag.auxiliary;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NameDateParser {

 // Regex to capture:
    // Group 1: Name (e.g., "George H. W. Bush") - anything before the first opening parenthesis.
    // Group 2: Birth Year (e.g., "1924" or "1961" from "b. 1961") - four digits inside the first parenthesis,
    //          optionally preceded by "b. ".
    // Group 3: Death Year (e.g., "2018") - optional, four digits after a hyphen/en-dash.
    // Group 4: Note Reference (e.g., "72") - optional, digits inside square brackets.
    private static final Pattern NAME_DATE_PATTERN = Pattern.compile(
        "(.+?)\\s*\\((?:b\\.\\h*)?(\\d{4})(?:[–-](\\d{4}))?\\)\\s*(?:\\[(\\d+)\\])?"
        // (.+?)        -> Group 1: Non-greedy match for any characters (the name)
        // \\s* -> Zero or more whitespace characters
        // \\(          -> Literal opening parenthesis
        // (?:b\\.\\s*)? -> Non-capturing optional group for "b. " (matches "b.", followed by optional whitespace)
        // (\\d{4})     -> Group 2: Four digits (birth year)
        // (?:          -> Non-capturing group for the optional death year part
        //    [–-]      -> Matches an en-dash (–) or a hyphen (-)
        //    (\\d{4})  -> Group 3: Four digits (death year)
        // )?           -> Makes the entire non-capturing group optional (0 or 1 occurrence)
        // \\)          -> Literal closing parenthesis
        // \\s* -> Zero or more whitespace characters
        // (?:          -> Non-capturing group for the optional note reference part
        //    \\[       -> Literal opening square bracket
        //    (\\d+)    -> Group 4: One or more digits (the note reference number)
        //    \\]       -> Literal closing square bracket
        // )?           -> Makes the entire non-capturing group optional (0 or 1 occurrence)
    );

    /**
     * Represents a parsed person's name with birth and death dates, and an optional note reference.
     */
    static class ParsedPersonDates {
        String name;
        Date birthDate; // Stored as a Date object for consistency, typically just the year for this pattern
        Date deathDate; // Stored as a Date object, typically just the year for this pattern
        String noteReference; // The numerical reference (e.g., "72")

        public ParsedPersonDates(String name, Date birthDate, Date deathDate, String noteReference) {
            this.name = name;
            this.birthDate = birthDate;
            this.deathDate = deathDate;
            this.noteReference = noteReference;
        }

        public String getName() { return name; }
        public Date getBirthDate() { return birthDate; }
        public Date getDeathDate() { return deathDate; }
        public String getNoteReference() { return noteReference; }

        @Override
        public String toString() {
            SimpleDateFormat yearFormat = new SimpleDateFormat("yyyy", Locale.ENGLISH);
            String birthYear = (birthDate != null) ? yearFormat.format(birthDate) : "N/A";
            String deathYear = (deathDate != null) ? yearFormat.format(deathDate) : "Present";
            
            StringBuilder dateRange = new StringBuilder("(");
            
            // For living people, display "b. YYYY" format if only birth date is present
            if (birthDate != null && deathDate == null) {
                dateRange.append("born ").append(birthYear);
            } else if (birthDate != null) {
                dateRange.append(birthYear);
                if (deathDate != null) {
                    dateRange.append("–").append(deathYear);
                }
            } else {
                dateRange.append("N/A"); // Fallback if no dates parsed
            }
            dateRange.append(")");

            StringBuilder result = new StringBuilder("Name: ").append(name)
                                     .append(", Dates: ").append(dateRange.toString());

            if (noteReference != null && !noteReference.isEmpty()) {
                result.append(", Note Ref: [").append(noteReference).append("]");
            }
            return result.toString();
        }
    }

    /**
     * Parses a string containing a person's name, birth year, optional death year, and optional note reference.
     *
     * @param input The input string (e.g., "George H. W. Bush (1924–2018) [72]").
     * @return A ParsedPersonDates object containing the extracted information, or null if parsing fails.
     */
    public static ParsedPersonDates parse(String input) {
        Matcher matcher = NAME_DATE_PATTERN.matcher(input);

        if (matcher.matches()) {
            String name = matcher.group(1).trim();
            String birthYearStr = matcher.group(2);
            String deathYearStr = matcher.group(3); // This will be null if no death year is present
            String noteReference = matcher.group(4); // This will be null if no note reference is present

            SimpleDateFormat yearFormat = new SimpleDateFormat("yyyy", Locale.ENGLISH);
            Date birthDate = null;
            Date deathDate = null;

            try {
                birthDate = yearFormat.parse(birthYearStr);
            } catch (ParseException e) {
                System.err.println("Error parsing birth year '" + birthYearStr + "': " + e.getMessage());
            }

            if (deathYearStr != null) {
                try {
                    deathDate = yearFormat.parse(deathYearStr);
                } catch (ParseException e) {
                    System.err.println("Error parsing death year '" + deathYearStr + "': " + e.getMessage());
                }
            }
            return new ParsedPersonDates(name, birthDate, deathDate, noteReference);
        } else {
            System.out.println("No match found for: " + input);
            return null;
        }
    }

    public static void main(String[] args) {
        String[] testStrings = {
            "George H. W. Bush (1924–2018) [72]",
            "Donald Trump (b. 1946) [45]", // Updated test case for living president
            "Abraham Lincoln (1809–1865)", // No note reference
            "John F. Kennedy (1917–1963) [35]",
            "Someone Alive (b. 1980)", // Another living person example, no note
            "No Dates Here", // Should not match
            "Invalid (ABCD-EFGH) [12]", // Invalid year format
            "James A. Garfield (1831–1881) [20]",
            "Barack Obama (b. 1961) [44]", // Updated test case for living president
            "Donald Trump (b. 1946) [76]"
        };

        for (String test : testStrings) {
            System.out.println("------------------------------------");
            System.out.println("Parsing: \"" + test + "\"");
            ParsedPersonDates result = parse(test);
            if (result != null) {
                System.out.println(result);
            }
        }
    }
}
