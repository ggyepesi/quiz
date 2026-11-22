package domain.workbench.modelbuilder.app;

import domain.workbench.modelbuilder.ui.StatementSummaryPanel;
import wikidata.WikidataSparqlClient;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.util.Arrays;

/** Standalone statement-summary window; it does not edit a model. */
public final class StatementSummaryApp {
    private StatementSummaryApp() { }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            WikidataSparqlClient client = new WikidataSparqlClient(
                    "quiz-statement-summary/1.0 (ggyepesi@gmail.com)");
            StatementSummaryPanel panel = new StatementSummaryPanel(client);
            JFrame frame = new JFrame("Statement summary (example-first) — #91");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(panel);
            frame.setSize(820, 720);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            panel.showFor(Arrays.asList(args.length > 0
                    ? args : new String[]{"Q103474"}));
        });
    }
}
