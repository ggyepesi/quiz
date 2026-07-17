package flag.auxiliary;

import javax.swing.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

// Assume this President class exists, similar to the one in your Canvas
// For demonstration, a simplified version:
class President {
    String name;
    String party;
    Calendar startDate;
    Calendar endDate;

    public President(String name, String party, int startYear, int endYear) {
        this.name = name;
        this.party = party;
        this.startDate = Calendar.getInstance();
        this.startDate.set(startYear, Calendar.JANUARY, 20); // Simplified to Jan 20
        this.endDate = Calendar.getInstance();
        this.endDate.set(endYear, Calendar.JANUARY, 20); // Simplified to Jan 20
    }

    // Getters
    public String getName() { return name; }
    public String getParty() { return party; }
    public Calendar getStartDate() { return startDate; }
    public Calendar getEndDate() { return endDate; }
}


public class ObjectListDisplay extends JFrame {

    public ObjectListDisplay() {
        super("List of Objects Display");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(600, 700);
        setLocationRelativeTo(null);

        // Sample list of President objects
        List<President> presidents = new ArrayList<>();
        presidents.add(new President("George Washington", "No Party", 1789, 1797));
        presidents.add(new President("John Adams", "Federalist", 1797, 1801));
        presidents.add(new President("Thomas Jefferson", "Democratic-Republican", 1801, 1809));
        presidents.add(new President("Abraham Lincoln", "Republican", 1861, 1865));
        presidents.add(new President("Franklin D. Roosevelt", "Democratic", 1933, 1945));
        presidents.add(new President("Joe Biden", "Democratic", 2021, 2025)); // Current

        // Main panel to hold individual object panels. Use BoxLayout for vertical stacking.
        JPanel mainDisplayPanel = new JPanel();
        mainDisplayPanel.setLayout(new BoxLayout(mainDisplayPanel, BoxLayout.Y_AXIS));
        mainDisplayPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Format for displaying dates
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH);

        // Iterate through the list of objects
        for (President president : presidents) {
            // Create a panel for each President object
            JPanel presidentPanel = new JPanel(new GridBagLayout()); // GridBagLayout for structured fields
            presidentPanel.setBorder(BorderFactory.createTitledBorder(president.getName())); // Title with name
            presidentPanel.setBackground(new Color(240, 240, 255)); // Light background for each entry

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5); // Padding around components
            gbc.fill = GridBagConstraints.HORIZONTAL; // Components fill horizontal space

            // Row 0: Name (already in title, but could be a field too)

            // Row 1: Party
            gbc.gridx = 0; // Column 0
            gbc.gridy = 0; // Row 0
            gbc.anchor = GridBagConstraints.WEST; // Align label to left
            presidentPanel.add(new JLabel("Party:"), gbc);

            gbc.gridx = 1; // Column 1
            gbc.weightx = 1.0; // Give extra horizontal space to text field
            presidentPanel.add(new JTextField(president.getParty(), 20), gbc);
            gbc.weightx = 0; // Reset weight for next row

            // Row 2: Start Date
            gbc.gridx = 0;
            gbc.gridy = 1;
            presidentPanel.add(new JLabel("Start Date:"), gbc);

            gbc.gridx = 1;
            gbc.weightx = 1.0;
            String startDateStr = (president.getStartDate() != null) ? dateFormat.format(president.getStartDate().getTime()) : "N/A";
            presidentPanel.add(new JTextField(startDateStr, 20), gbc);
            gbc.weightx = 0;

            // Row 3: End Date
            gbc.gridx = 0;
            gbc.gridy = 2;
            presidentPanel.add(new JLabel("End Date:"), gbc);

            gbc.gridx = 1;
            gbc.weightx = 1.0;
            String endDateStr = (president.getEndDate() != null) ? dateFormat.format(president.getEndDate().getTime()) : "Current";
            presidentPanel.add(new JTextField(endDateStr, 20), gbc);
            gbc.weightx = 0;

            // Add some vertical glue/padding at the bottom of each president's panel
            gbc.gridx = 0;
            gbc.gridy = 3;
            gbc.gridwidth = 2; // Span two columns
            gbc.weighty = 1.0; // Push components to the top
            presidentPanel.add(Box.createVerticalGlue(), gbc);
            gbc.weighty = 0; // Reset weight

            // Add the individual president's panel to the main display panel
            mainDisplayPanel.add(presidentPanel);
            mainDisplayPanel.add(Box.createVerticalStrut(10)); // Add a small vertical space between entries
        }

        // Wrap the main display panel in a JScrollPane to handle many entries
        JScrollPane scrollPane = new JScrollPane(mainDisplayPanel);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER); // No horizontal scroll

        add(scrollPane, BorderLayout.CENTER);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new ObjectListDisplay().setVisible(true);
        });
    }
}