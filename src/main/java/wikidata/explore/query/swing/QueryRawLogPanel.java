package wikidata.explore.query.swing;

import wikidata.explore.query.core.*;

import javax.swing.*;
import java.awt.*;
import java.util.Map;

public class QueryRawLogPanel extends JPanel implements TextQueryEventSink {

    private final JTextField searchField = new JTextField(18);
    private final JTextArea area = new JTextArea();

    public QueryRawLogPanel() {
        super(new BorderLayout(4, 4));

        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        top.add(new JLabel("Search:"));
        top.add(searchField);
        JButton findButton = new JButton("Find");
        top.add(findButton);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(area), BorderLayout.CENTER);

        findButton.addActionListener(e -> findNext());
        searchField.addActionListener(e -> findNext());
    }

    @Override
    public void text(String text) {
        appendRaw(text);
    }

    @Override
    public void accept(QueryEvent e) {
        SwingUtilities.invokeLater(() -> {
            if (e.status() == QueryStatus.RUNNING) {
                appendStart(e);
            } else {
                appendFinish(e);
            }
        });
    }

    public void appendRaw(String s) {
        SwingUtilities.invokeLater(() -> {
            area.append(s == null ? "" : s);
            if (s != null && !s.endsWith("\n")) {
                area.append("\n");
            }
            scroll();
        });
    }
    private void appendStart(QueryEvent e) {
        Query<?> q = e.query();

        area.append("\n[Q" + e.id() + "] START "
                            + (q == null ? "" : q.purpose())
                            + "\n");

        if (q != null && !q.skeleton().isBlank()) {
            area.append("Skeleton: " + q.skeleton() + "\n");
        }

        if (q != null && !q.parameters().isEmpty()) {
            area.append("Parameters:\n");
            for (Map.Entry<String, String> p : q.parameters().entrySet()) {
                area.append("  " + p.getKey() + " = " + p.getValue() + "\n");
            }
        }

        scroll();
    }

    private void appendFinish(QueryEvent e) {
        area.append("[Q" + e.id() + "] "
                            + e.status()
                            + " rows="
                            + e.rowCount()
                            + " timeMs="
                            + e.timeMs()
                            + (e.error().isBlank() ? "" : " error=" + e.error())
                            + "\n");
        scroll();
    }

    private void findNext() {
        String needle = searchField.getText();
        if (needle == null || needle.isBlank()) return;

        String haystack = area.getText();
        int start = area.getCaretPosition();
        int pos = haystack.indexOf(needle, start);

        if (pos < 0) pos = haystack.indexOf(needle);

        if (pos >= 0) {
            area.requestFocusInWindow();
            area.select(pos, pos + needle.length());
        }
    }

    private void scroll() {
        area.setCaretPosition(area.getDocument().getLength());
    }
}