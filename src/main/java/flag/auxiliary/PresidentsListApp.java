package flag.auxiliary;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar; // Import Calendar
import java.util.Date; // Still needed for SimpleDateFormat.parse() return type
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


// Can't find the presidents table
public class PresidentsListApp extends JFrame {

    private static final String WIKIPEDIA_URL = "https://en.wikipedia.org/wiki/List_of_presidents_of_the_United_States";

    // Regex to capture:
    // Group 1: Name (e.g., "George H. W. Bush") - anything before the first opening parenthesis.
    // Group 2: Birth Year (e.g., "1924" or "1961" from "b. 1961") - four digits inside the first parenthesis,
    //          optionally preceded by "b. ".
    // Group 3: Death Year (e.g., "2018") - optional, four digits after a hyphen/en-dash.
    // Group 4: Note Reference (e.g., "72") - optional, digits inside square brackets.
    private static final Pattern NAME_DATE_NOTE_PATTERN = Pattern.compile(
        "(.+?)\\s*\\((?:b\\.\\h*)?(\\d{4})(?:[–-](\\d{4}))?\\)\\s*(?:\\[(\\d+)\\])?"
    );


    /**
     * Represents a US President with their name, party, term dates, and associated notes.
     */
    static class President {
        String name;
        String party;
        Calendar startDate; // Changed from Date to Calendar
        Calendar endDate;   // Changed from Date to Calendar
        String birthYearRaw; // Keep original raw year string for display in case date parsing fails
        String deathYearRaw; // Keep original raw year string for display in case date parsing fails
        String noteReference; // The numerical reference (e.g., "72")
        String noteText;      // The actual text of the referenced note

        public President(String name, String party, Calendar startDate, Calendar endDate, // Changed to Calendar
                         String birthYearRaw, String deathYearRaw, String noteReference, String noteText) {
            this.name = name;
            this.party = party;
            this.startDate = startDate;
            this.endDate = endDate;
            this.birthYearRaw = birthYearRaw;
            this.deathYearRaw = deathYearRaw;
            this.noteReference = noteReference;
            this.noteText = noteText;
        }

        public String getName() { return name; }
        public String getParty() { return party; }
        public Calendar getStartDate() { return startDate; } // Changed return type
        public Calendar getEndDate() { return endDate; }   // Changed return type
        public String getNoteReference() { return noteReference; }
        public String getNoteText() { return noteText; }
        public String getBirthYearRaw() { return birthYearRaw; }
        public String getDeathYearRaw() { return deathYearRaw; }

        @Override
        public String toString() {
            SimpleDateFormat displayFormat = new SimpleDateFormat("MMM d,yyyy", Locale.ENGLISH);
            String startStr = (startDate != null) ? displayFormat.format(startDate.getTime()) : "N/A"; // Use getTime()
            String endStr = (endDate != null) ? displayFormat.format(endDate.getTime()) : "Current"; // Use getTime()

            StringBuilder termDisplay = new StringBuilder(startStr);
            if (endDate != null) {
                termDisplay.append(" – ").append(endStr);
            }

            StringBuilder nameAndDates = new StringBuilder(name);
            nameAndDates.append(" (");
            if (birthYearRaw != null && deathYearRaw == null) {
                nameAndDates.append("b. ").append(birthYearRaw);
            } else if (birthYearRaw != null) {
                nameAndDates.append(birthYearRaw);
                if (deathYearRaw != null) {
                    nameAndDates.append("–").append(deathYearRaw);
                }
            } else {
                nameAndDates.append("N/A"); // Fallback if no dates parsed
            }
            nameAndDates.append(")");


            StringBuilder display = new StringBuilder();
            display.append(String.format("%s (%s) - %s", nameAndDates.toString(), party, termDisplay.toString()));

            if (noteReference != null && !noteReference.isEmpty()) {
                display.append(" [").append(noteReference).append("]");
            }
            if (noteText != null && !noteText.isEmpty()) {
                display.append(" Note: ").append(noteText);
            }
            return display.toString();
        }
    }

    /**
     * Custom ListCellRenderer to display President objects with party symbols and formatted dates/notes.
     */
    static class PresidentListCellRenderer extends DefaultListCellRenderer {
        private static final Map<String, String> PARTY_SYMBOLS = new HashMap<>();
        private static final Map<String, Color> PARTY_COLORS = new HashMap<>();

        static {
            PARTY_SYMBOLS.put("Democratic", "D");
            PARTY_SYMBOLS.put("Republican", "R");
            PARTY_SYMBOLS.put("Democratic-Republican", "DR");
            PARTY_SYMBOLS.put("Federalist", "F");
            PARTY_SYMBOLS.put("Whig", "W");
            PARTY_SYMBOLS.put("National Union", "NU");
            PARTY_SYMBOLS.put("Independent", "I");
            PARTY_SYMBOLS.put("National Republican", "NR");
            PARTY_SYMBOLS.put("No party", "N/A");
            PARTY_SYMBOLS.put("Union", "U");
            // Add more as needed for historical parties

            PARTY_COLORS.put("Democratic", new Color(0, 0, 150));
            PARTY_COLORS.put("Republican", new Color(150, 0, 0));
            PARTY_COLORS.put("Federalist", new Color(50, 100, 50));
            PARTY_COLORS.put("Whig", new Color(150, 150, 0));
            PARTY_COLORS.put("Democratic-Republican", new Color(0, 100, 100));
            PARTY_COLORS.put("Independent", new Color(100, 100, 100));
            PARTY_COLORS.put("National Union", new Color(100, 50, 150));
            PARTY_COLORS.put("National Republican", new Color(100, 100, 0));
            PARTY_COLORS.put("No party", new Color(120, 120, 120));
            PARTY_COLORS.put("Union", new Color(100, 50, 150));
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

            if (value instanceof President) {
                President president = (President) value;
                String partySymbol = PARTY_SYMBOLS.getOrDefault(president.getParty(), "?");

                SimpleDateFormat displayFormat = new SimpleDateFormat("MMM d,yyyy", Locale.ENGLISH);
                String startStr = (president.getStartDate() != null) ? displayFormat.format(president.getStartDate().getTime()) : "N/A"; // Use getTime()
                String endStr = (president.getEndDate() != null) ? displayFormat.format(president.getEndDate().getTime()) : "Current"; // Use getTime()

                StringBuilder termDisplay = new StringBuilder(startStr);
                if (president.getEndDate() != null) {
                    termDisplay.append(" – ").append(endStr);
                }

                // Format name and dates based on birth/death presence
                StringBuilder nameAndDates = new StringBuilder(president.getName());
                nameAndDates.append(" (");
                if (president.getBirthYearRaw() != null && president.getDeathYearRaw() == null) {
                    nameAndDates.append("b. ").append(president.getBirthYearRaw());
                } else if (president.getBirthYearRaw() != null) {
                    nameAndDates.append(president.getBirthYearRaw());
                    if (president.getDeathYearRaw() != null) {
                        nameAndDates.append("–").append(president.getDeathYearRaw());
                    }
                } else {
                    nameAndDates.append("N/A");
                }
                nameAndDates.append(")");


                String displayText = String.format("%-4s %s (%s) - %s",
                                                   "[" + partySymbol + "]", // Padded symbol
                                                   nameAndDates.toString(),
                                                   president.getParty(),
                                                   termDisplay.toString());
                
                // Append note if available
                if (president.getNoteText() != null && !president.getNoteText().isEmpty()) {
                    displayText += " [Note " + president.getNoteReference() + ": " + president.getNoteText() + "]";
                }

                label.setText(displayText);
                label.setForeground(PARTY_COLORS.getOrDefault(president.getParty(), Color.BLACK));
                label.setFont(new Font("Arial", Font.PLAIN, 14));
                label.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
            }
            return label;
        }
    }

    public PresidentsListApp() {
        super("US Presidents List");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 700); // Increased width to accommodate notes
        setLocationRelativeTo(null);

        // UI Components
        JLabel titleLabel = new JLabel("Presidents of the United States", SwingConstants.CENTER);
        titleLabel.setFont(new Font("Serif", Font.BOLD, 28));
        titleLabel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        DefaultListModel<President> listModel = new DefaultListModel<>();
        JList<President> presidentList = new JList<>(listModel);
        presidentList.setCellRenderer(new PresidentListCellRenderer());
        presidentList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane scrollPane = new JScrollPane(presidentList);

        // Layout the components
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.add(titleLabel, BorderLayout.NORTH);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        getContentPane().add(mainPanel);

        JLabel loadingLabel = new JLabel("Loading presidents data...", SwingConstants.CENTER);
        loadingLabel.setFont(new Font("Arial", Font.ITALIC, 16));
        mainPanel.add(loadingLabel, BorderLayout.SOUTH);

        SwingWorker<List<President>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<President> doInBackground() throws Exception {
                // Fetch the document once
                Document doc = Jsoup.connect(WIKIPEDIA_URL).get();
                // Parse notes first, as presidents will reference them
                Map<String, String> parsedNotes = parseNotes(doc);
                // Then parse presidents, passing the notes map
                return parsePresidents(doc, parsedNotes);
            }

            @Override
            protected void done() {
                try {
                    List<President> presidents = get();
                    for (President p : presidents) {
                        listModel.addElement(p);
                    }
                    loadingLabel.setText("Data loaded successfully.");
                } catch (Exception e) {
                    loadingLabel.setText("Error loading data: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    mainPanel.remove(loadingLabel);
                    mainPanel.revalidate();
                    mainPanel.repaint();
                }
            }
        };
        worker.execute();
    }

    /**
     * Parses the notes (footnotes/references) section of the Wikipedia page.
     * @param doc The Jsoup Document of the Wikipedia page.
     * @return A Map where key is the note number (e.g., "90") and value is the note text.
     */
    private Map<String, String> parseNotes(Document doc) {
        Map<String, String> notesMap = new HashMap<>();
        // Wikipedia footnotes are usually in a div with class "reflist"
        // or directly in an ol with class "references"
        Elements noteListItems = doc.select("div.mw-references-columns li[id^=cite_note-], ol.references li[id^=cite_note-]");

        for (Element listItem : noteListItems) {
            String id = listItem.attr("id"); // e.g., "cite_note-90"
            // Extract the number from the ID
            Pattern noteIdPattern = Pattern.compile("cite_note-(\\d+)");
            Matcher matcher = noteIdPattern.matcher(id);
            if (matcher.find()) {
                String noteNumber = matcher.group(1);
                // The actual note text is often within a span or directly in the li,
                // and we need to remove the back-reference link (usually "[a]" or "^").
                // Selecting content after the first <a> tag (the back-reference) or just the text.
                String noteContent = listItem.text();
                // Remove the back-reference (e.g., "[90]^ a b")
                noteContent = noteContent.replaceAll("^\\[\\d+\\][\\s\\S]*?[\\^abcde]+\\s*", "").trim();
                // Remove any remaining bracketed numbers/letters from the start
                noteContent = noteContent.replaceAll("^\\[[a-zA-Z0-9]+\\]\\s*", "").trim();

                // Get the text content, excluding other links if possible for cleaner text
                // Attempt to get text directly, or the text of the first meaningful element
                Element contentElement = listItem.select("span.reference-text, span.mw-reference-text").first();
                if (contentElement == null) {
                    // Fallback: get text of the li itself and clean it
                    contentElement = listItem;
                }
                String extractedText = contentElement.text();
                // Further cleanup for references like "[90]" at the very beginning of the note text
                extractedText = extractedText.replaceAll("^\\[\\d+\\][\\s\\S]*?[\\^abcde]*", "").trim();
                extractedText = extractedText.replaceAll("\\[[0-9]+\\]", "").trim(); // Remove any remaining bracketed numbers

                notesMap.put(noteNumber, extractedText);
            }
        }
        return notesMap;
    }

    /**
     * Parses the main presidents table to extract president data.
     * This method now uses the NAME_DATE_NOTE_PATTERN for name/date parsing
     * and links note references to actual note text.
     *
     * @param doc The Jsoup Document of the Wikipedia page.
     * @param allNotes A map of all parsed notes (number to text).
     * @return A list of President objects.
     * @throws IOException (not directly thrown here, but kept for signature consistency with Jsoup calls)
     */
    private List<President> parsePresidents(Document doc, Map<String, String> allNotes) throws IOException {
        List<President> presidents = new ArrayList<>();

        Elements tables = doc.select("table.wikitable");
        Element presidentsTable = null;

        for (Element table : tables) {
            if (table.text().contains("President") && table.text().contains("Term") && table.text().contains("Political party")) {
                presidentsTable = table;
                break;
            }
        }

        if (presidentsTable == null) {
            System.err.println("Could not find the main presidents table on the page.");
            return presidents;
        }

        Elements rows = presidentsTable.select("tr");
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMMM d,yyyy", Locale.ENGLISH);
        SimpleDateFormat yearFormat = new SimpleDateFormat("yyyy", Locale.ENGLISH);


        for (int i = 0; i < rows.size(); i++) {
            Element row = rows.get(i);
            if (row.select("th").size() > 0 && !row.select("th").first().hasAttr("rowspan")) {
                continue;
            }
            if (row.select("td").isEmpty()) {
                continue;
            }

            Elements cells = row.select("td");
            if (cells.size() < 4) {
                continue;
            }

            String presidentNameAndDatesRaw = "";
            String partyName = "";
            String termYearsRaw = "";

            // President Name: Usually in the second td (index 1), may contain the (dates) [note] string
            Element nameCell = cells.get(1);
            presidentNameAndDatesRaw = nameCell.text().trim(); // Get full text, including name, dates, and notes

            // Party Name: Usually in the third td (index 2)
            Element partyCell = cells.get(2);
            Element partyLink = partyCell.select("a[title]").first();
            if (partyLink != null) {
                partyName = partyLink.text().trim();
            } else {
                partyName = partyCell.text().trim();
            }

            // Term Years: Usually in the fourth td (index 3)
            Element termCell = cells.get(3);
            termYearsRaw = termCell.text().trim();
            termYearsRaw = termYearsRaw.replaceAll("\\[[0-9]+\\]", "").trim();

            // --- Apply the NAME_DATE_NOTE_PATTERN to extract name, birth/death year, and note reference ---
            Matcher matcher = NAME_DATE_NOTE_PATTERN.matcher(presidentNameAndDatesRaw);
            String name = "";
            Date tempBirthDate = null; // Use temporary Date for parsing
            Date tempDeathDate = null; // Use temporary Date for parsing
            String birthYearStr = null;
            String deathYearStr = null;
            String noteReference = null;
            String noteText = null;

            if (matcher.matches()) {
                name = matcher.group(1).trim();
                birthYearStr = matcher.group(2);
                deathYearStr = matcher.group(3);
                noteReference = matcher.group(4);

                try {
                    tempBirthDate = yearFormat.parse(birthYearStr);
                } catch (ParseException e) {
                    System.err.println("Error parsing birth year '" + birthYearStr + "' for " + name + ": " + e.getMessage());
                }

                if (deathYearStr != null) {
                    try {
                        tempDeathDate = yearFormat.parse(deathYearStr);
                    } catch (ParseException e) {
                        System.err.println("Error parsing death year '" + deathYearStr + "' for " + name + ": " + e.getMessage());
                    }
                }
            } else {
                 // Fallback if the regex doesn't match the full string.
                 // This might happen for older entries or simpler name formats.
                name = presidentNameAndDatesRaw.split("\\(")[0].trim(); // Take everything before the first parenthesis as name
            }

            // Get the actual note text from the map
            if (noteReference != null && allNotes.containsKey(noteReference)) {
                noteText = allNotes.get(noteReference);
            }

            Calendar termStartDate = null; // Changed to Calendar
            Calendar termEndDate = null;   // Changed to Calendar

            String[] dateParts = termYearsRaw.split("–|\\s-\\s");
            if (dateParts.length == 2) {
                try {
                    Date parsedStart = dateFormat.parse(dateParts[0].trim());
                    termStartDate = Calendar.getInstance();
                    termStartDate.setTime(parsedStart);

                    Date parsedEnd = dateFormat.parse(dateParts[1].trim());
                    termEndDate = Calendar.getInstance();
                    termEndDate.setTime(parsedEnd);

                } catch (ParseException e) {
                    System.err.println("Term date parsing error (start-end) for: " + name + " term '" + termYearsRaw + "': " + e.getMessage());
                }
            } else if (dateParts.length == 1 && !dateParts[0].trim().isEmpty()) {
                try {
                    Date parsedStart = dateFormat.parse(dateParts[0].trim());
                    termStartDate = Calendar.getInstance();
                    termStartDate.setTime(parsedStart);
                } catch (ParseException e) {
                    try {
                        Date parsedStartYear = yearFormat.parse(dateParts[0].trim());
                        termStartDate = Calendar.getInstance();
                        termStartDate.setTime(parsedStartYear);
                    } catch (ParseException e2) {
                        System.err.println("Single term date parsing error for: " + name + " term '" + termYearsRaw + "': " + e2.getMessage());
                    }
                }
            }

            if (!name.isEmpty() && !name.contains("Vacant") && !name.contains("acting")) {
                presidents.add(new President(name, partyName, termStartDate, termEndDate,
                                             birthYearStr, deathYearStr, noteReference, noteText));
            }
        }
        return presidents;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            PresidentsListApp app = new PresidentsListApp();
            app.setVisible(true);
        });
    }
}
