package benchmark;

import objectview.viewconfig.ViewablePanelConfig;
import oscar.OscarNomination;
import objectview.viewconfig.ViewablePanelConfigAdapter;
import objectview.ViewablePanel;
import wikidata.explore.extract.WikidataDynamicObject;

import javax.swing.*;
import java.awt.*;

public class VisualComparisonApp {

    public static void main(String[] args) {
        // 1. Ensure the UI looks clean on your operating system
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        // 2. Fabricate a single realistic target record matching our benchmark
        WikidataDynamicObject brando = WikidataDynamicObject.canonical("Marlon Brando",
                                                      "Q16122");
        WikidataDynamicObject award = WikidataDynamicObject.canonical("Best Actor",
                                                        "Q103916");
        WikidataDynamicObject movie = WikidataDynamicObject.canonical("The Godfather",
                                                      "Q47703");

        OscarNomination targetRecord = new OscarNomination();
        targetRecord.setNominee(brando);
        targetRecord.setAward(award);
        targetRecord.setWork(movie);
        targetRecord.setCeremonyYear(1973);
        targetRecord.setFilmYear(1972);
        targetRecord.setWinner(true);

        // 3. Generate the exact full configuration frame rules
        ViewablePanelConfig fullConfig = ViewablePanelConfigAdapter.fromOldArgs(
                targetRecord, true, true, true
                                                                               );

        // 4. Construct the Main Frame Wrapper
        JFrame frame = new JFrame("ViewablePanel Visual Verification");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        // The live reflection-driven ViewablePanel view (the only renderer now).
        JPanel container = new JPanel(new BorderLayout());
        container.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        container.add(new JLabel("Reflection Loop (ViewablePanel)", SwingConstants.CENTER), BorderLayout.NORTH);

        ViewablePanel panel = new ViewablePanel(targetRecord, fullConfig, false);
        container.add(panel, BorderLayout.CENTER);

        // 5. Pack and display the frame window
        frame.add(container, BorderLayout.CENTER);
        frame.pack();
        frame.setMinimumSize(new Dimension(800, 500));
        frame.setLocationRelativeTo(null); // Centered on window screen
        frame.setVisible(true);
    }
}