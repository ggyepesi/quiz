package benchmark;

import benchmark.generated.OscarNominationFormView;
import oscar.OscarNomination;
import quiz.QuizablePanelConfig;
import quiz.QuizablePanelConfigAdapter;
import quiz.ui.QuizablePanel;
import wikidata.WikidataEntity;

import javax.swing.*;
import java.awt.*;

public class VisualComparisonApp {

    public static void main(String[] args) {
        // 1. Ensure the UI looks clean on your operating system
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        // 2. Fabricate a single realistic target record matching our benchmark
        WikidataEntity brando = WikidataEntity.canonical("Marlon Brando",
                                                      "Q16122");
        WikidataEntity award = WikidataEntity.canonical("Best Actor",
                                                        "Q103916");
        WikidataEntity movie = WikidataEntity.canonical("The Godfather",
                                                      "Q47703");

        OscarNomination targetRecord = new OscarNomination();
        targetRecord.setNominee(brando);
        targetRecord.setAward(award);
        targetRecord.setWork(movie);
        targetRecord.setCeremonyYear(1973);
        targetRecord.setFilmYear(1972);
        targetRecord.setWinner(true);

        // 3. Generate the exact full configuration frame rules
        QuizablePanelConfig fullConfig = QuizablePanelConfigAdapter.fromOldArgs(
                targetRecord, true, true, true
        );

        // 4. Construct the Main Frame Wrapper
        JFrame frame = new JFrame("FormStamper Visual Sync Verification");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new GridLayout(1, 2, 20, 0)); // Side-by-side view with a 20px gap

        // --- Left Panel: Your Live Runtime QuizablePanel View ---
        JPanel leftContainer = new JPanel(new BorderLayout());
        leftContainer.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        leftContainer.add(new JLabel("1. Legacy Reflection Loop (QuizablePanel)", SwingConstants.CENTER), BorderLayout.NORTH);

        // Dynamic reflection execution block
        QuizablePanel legacyPanel = new QuizablePanel(targetRecord, fullConfig, false);
        leftContainer.add(legacyPanel, BorderLayout.CENTER);

        // --- Right Panel: The Cleaned, Hardcoded FormView ---
        JPanel rightContainer = new JPanel(new BorderLayout());
        rightContainer.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        rightContainer.add(new JLabel("2. Compiled Bytecode View (FormStamper)", SwingConstants.CENTER), BorderLayout.NORTH);

        // FIX: No map parameters, no configuration objects passed. Just the raw data!
        OscarNominationFormView compiledView = new OscarNominationFormView(targetRecord);
        rightContainer.add(compiledView, BorderLayout.CENTER);

        // 5. Pack and display the frame window
        frame.add(leftContainer);
        frame.add(rightContainer);
        frame.pack();
        frame.setMinimumSize(new Dimension(800, 500));
        frame.setLocationRelativeTo(null); // Centered on window screen
        frame.setVisible(true);
    }
}