package flag.auxiliary;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WikipediaPersonParser {

    /**
     * Represents a person with their name, birth date, and death date.
     */
    static class Person {
        String name;
        Calendar birthDate;
        Calendar deathDate;
        String occupation; // Example of another field you might want to extract
        String nationality; // Example of another field

        public Person(String name, Calendar birthDate, Calendar deathDate, String occupation, String nationality) {
            this.name = name;
            this.birthDate = birthDate;
            this.deathDate = deathDate;
            this.occupation = occupation;
            this.nationality = nationality;
        }

        public String getName() { return name; }
        public Calendar getBirthDate() { return birthDate; }
        public Calendar getDeathDate() { return deathDate; }
        public String getOccupation() { return occupation; }
        public String getNationality() { return nationality; }

        @Override
        public String toString() {
            SimpleDateFormat dateFormat = new SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH);
            StringBuilder sb = new StringBuilder("Person:\n");
            sb.append("  Name: ").append(name).append("\n");
            sb.append("  Birth Date: ").append(birthDate != null ? dateFormat.format(birthDate.getTime()) : "N/A").append("\n");
            sb.append("  Death Date: ").append(deathDate != null ? dateFormat.format(deathDate.getTime()) : "N/A").append("\n");
            sb.append("  Occupation: ").append(occupation != null && !occupation.isEmpty() ? occupation : "N/A").append("\n");
            sb.append("  Nationality: ").append(nationality != null && !nationality.isEmpty() ? nationality : "N/A").append("\n");
            return sb.toString();
        }
    }

    /**
     * Parses a Wikipedia page for a person to extract biographical data from the infobox.
     * This method targets the common "infobox vcard" structure.
     *
     * @param wikipediaUrl The URL of the Wikipedia page.
     * @return A Person object with parsed data, or null if parsing fails.
     */
    public Person parsePersonData(String wikipediaUrl) {
        try {
            Document doc = Jsoup.connect(wikipediaUrl).get();

            // Most person pages have an infobox with class "infobox vcard"
            Element infobox = doc.select("table.infobox.vcard").first();

            if (infobox == null) {
                System.err.println("Infobox not found on page: " + wikipediaUrl);
                return null;
            }

            String name = "";
            Calendar birthDate = null;
            Calendar deathDate = null;
            String occupation = "";
            String nationality = "";

            // Extract Name (usually the first <th> or <caption> in the infobox)
            Element nameElement = infobox.select("caption, th.fn").first(); // fn is for full name
            if (nameElement != null) {
                name = nameElement.text().trim();
            } else {
                // Fallback: try to get the page title
                name = doc.select("h1#firstHeading").text().trim();
            }

            // Extract Birth Date and Death Date
            // Look for <td> or <li> elements containing "Born" or "Died" or "b." or "d."
            // Wikipedia uses <span class="bday"> for birth date in YYYY-MM-DD format
            // And often has dates in <td> elements.

            // Parsing birth date (YYYY-MM-DD format from <span class="bday">)
            Element bdaySpan = infobox.select("span.bday").first();
            if (bdaySpan != null) {
                String bdayStr = bdaySpan.text().trim(); // e.g., "1927-06-21"
                try {
                    SimpleDateFormat bdayFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);
                    Date parsedDate = bdayFormat.parse(bdayStr);
                    birthDate = Calendar.getInstance();
                    birthDate.setTime(parsedDate);
                } catch (ParseException e) {
                    System.err.println("Error parsing birth date from bday span: " + bdayStr + " - " + e.getMessage());
                }
            } else {
                // Fallback: Look for "Born" or "b." in infobox rows
                Elements bornElements = infobox.select("th:contains(Born) + td, tr:has(th:contains(Born)) td, li:contains(b.)");
                for (Element bornEl : bornElements) {
                    String dateText = bornEl.text().trim();
                    // Try to extract a date pattern (e.g., "Month Day, Year")
                    // Example: "June 21, 1927"
                    Pattern datePattern = Pattern.compile("([A-Za-z]+ \\d{1,2}, \\d{4})");
                    Matcher matcher = datePattern.matcher(dateText);
                    if (matcher.find()) {
                        try {
                            SimpleDateFormat generalDateFormat = new SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH);
                            Date parsedDate = generalDateFormat.parse(matcher.group(1));
                            birthDate = Calendar.getInstance();
                            birthDate.setTime(parsedDate);
                            break; // Found it, stop searching
                        } catch (ParseException e) {
                            System.err.println("Error parsing general birth date: " + matcher.group(1) + " - " + e.getMessage());
                        }
                    }
                }
            }

            // Parsing death date (similar logic)
            Elements diedElements = infobox.select("th:contains(Died) + td, tr:has(th:contains(Died)) td, li:contains(d.)");
            for (Element diedEl : diedElements) {
                String dateText = diedEl.text().trim();
                Pattern datePattern = Pattern.compile("([A-Za-z]+ \\d{1,2}, \\d{4})");
                Matcher matcher = datePattern.matcher(dateText);
                if (matcher.find()) {
                    try {
                        SimpleDateFormat generalDateFormat = new SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH);
                        Date parsedDate = generalDateFormat.parse(matcher.group(1));
                        deathDate = Calendar.getInstance();
                        deathDate.setTime(parsedDate);
                        break; // Found it, stop searching
                    } catch (ParseException e) {
                        System.err.println("Error parsing general death date: " + matcher.group(1) + " - " + e.getMessage());
                    }
                }
            }

            // Extract Occupation
            Elements occupationElements = infobox.select("th:contains(Occupation) + td, tr:has(th:contains(Occupation)) td");
            if (!occupationElements.isEmpty()) {
                occupation = occupationElements.first().text().trim();
                // Clean up any bracketed references like [1]
                occupation = occupation.replaceAll("\\[\\d+\\]", "").trim();
            }

            // Extract Nationality
            Elements nationalityElements = infobox.select("th:contains(Nationality) + td, tr:has(th:contains(Nationality)) td");
            if (!nationalityElements.isEmpty()) {
                nationality = nationalityElements.first().text().trim();
                nationality = nationality.replaceAll("\\[\\d+\\]", "").trim();
            }

            return new Person(name, birthDate, deathDate, occupation, nationality);

        } catch (IOException e) {
            System.err.println("Error fetching or parsing page: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    public static void main(String[] args) {
        WikipediaPersonParser parser = new WikipediaPersonParser();

        // Test with George Andrew Olah's page
        String olahUrl = "https://en.wikipedia.org/wiki/George_Andrew_Olah";
        System.out.println("Parsing data for: " + olahUrl);
        Person olah = parser.parsePersonData(olahUrl);
        if (olah != null) {
            System.out.println(olah);
        } else {
            System.out.println("Failed to parse data for George Andrew Olah.");
        }

        System.out.println("\n-------------------------------------\n");

        // Test with a living person (e.g., Barack Obama)
        String obamaUrl = "https://en.wikipedia.org/wiki/Barack_Obama";
        System.out.println("Parsing data for: " + obamaUrl);
        Person obama = parser.parsePersonData(obamaUrl);
        if (obama != null) {
            System.out.println(obama);
        } else {
            System.out.println("Failed to parse data for Barack Obama.");
        }

        System.out.println("\n-------------------------------------\n");

        // Test with a different person (e.g., Marie Curie)
        String curieUrl = "https://en.wikipedia.org/wiki/Marie_Curie";
        System.out.println("Parsing data for: " + curieUrl);
        Person curie = parser.parsePersonData(curieUrl);
        if (curie != null) {
            System.out.println(curie);
        } else {
            System.out.println("Failed to parse data for Marie Curie.");
        }
    }
}
