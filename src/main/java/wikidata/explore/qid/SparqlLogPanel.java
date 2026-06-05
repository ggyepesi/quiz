package wikidata.explore.qid;

import javax.swing.*;
import java.awt.*;

public class SparqlLogPanel extends JPanel {

    private final JTextArea textArea =
            new JTextArea(10, 100);

    public SparqlLogPanel() {
        super(new BorderLayout());

        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        add(new JScrollPane(textArea), BorderLayout.CENTER);
    }

    public void clear() {
        textArea.setText("");
    }

    public void logQuery(String title, String sparql) {
        append("\n" + title + "\n");
        append("-".repeat(Math.max(8, title.length())) + "\n");
        append(sparql);
        append("\n");
    }

    public void logSummary(String title, int count) {
        append(title + ": " + count + " row(s)\n");
    }

    public void logSummary(String title, int count, long millis) {
        append(title
                       + ": "
                       + count
                       + " row(s), "
                       + millis
                       + " ms\n");
    }

    public void logCancelled(String title, long millis) {
        append(title + ": cancelled after " + millis + " ms\n");
    }

    public void logMessage(String message) {
        append(message + "\n");
    }

    public void logError(String title, Exception ex) {
        append(title + " ERROR: " + ex.getMessage() + "\n");
    }

    private void append(String text) {
        SwingUtilities.invokeLater(() -> {
            textArea.append(text);
            textArea.setCaretPosition(textArea.getDocument().getLength());
        });
    }
}
