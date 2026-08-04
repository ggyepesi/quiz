package wikidata.explore.workbench;

import wikidata.WikidataIds;

import wikidata.WikidataSparqlClient;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Example-first statement view (GitHub #91): enter or push in a few sample QIDs
 * and see, in two tabs:
 * <ul>
 *   <li><b>Configure</b> — the MERGED coverage (property → qualifiers, badged
 *       with how many samples/statements carry each) as <b>clickable rows</b>;
 *       a {@code +} configures a field / qualifier field via the modelbuilder's
 *       Add-Field path.</li>
 *   <li><b>Values</b> — each sample entity's statements with the actual value
 *       <em>labels</em>, so you can see what the values are (pick an object
 *       class, sanity-check).</li>
 * </ul>
 * The loaded QIDs render as clickable chips that open the wiki page. Coverage
 * over a few samples is a directional hint, not the aggregate verdict.
 */
public final class StatementSummaryPanel extends JPanel {

    private final WikidataSparqlClient client;
    private final JTextField qidsField = new JTextField(24);
    private final JLabel status = new JLabel(" ");
    private final JPanel chipsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    private final JPanel rowsHost = new JPanel();
    private final JTextArea valuesArea = new JTextArea();

    private BiConsumer<String, String> onConfigureField = (pid, label) -> { };
    private BiConsumer<String, String> onConfigureQualifier = (pid, label) -> { };

    public StatementSummaryPanel(WikidataSparqlClient client) {
        this.client = client;
        setLayout(new BorderLayout(6, 6));

        JPanel input = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        input.add(new JLabel("Sample QIDs:"));
        input.add(qidsField);
        JButton load = new JButton("Load");
        load.addActionListener(e -> load(parseQids(qidsField.getText())));
        input.add(load);
        input.add(status);

        JPanel north = new JPanel(new BorderLayout());
        north.add(input, BorderLayout.NORTH);
        north.add(chipsRow, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);

        rowsHost.setLayout(new BoxLayout(rowsHost, BoxLayout.Y_AXIS));
        JScrollPane configScroll = new JScrollPane(rowsHost);
        configScroll.getVerticalScrollBar().setUnitIncrement(16);

        valuesArea.setEditable(false);
        valuesArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane valuesScroll = new JScrollPane(valuesArea);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Configure", configScroll);
        tabs.addTab("Values", valuesScroll);
        add(tabs, BorderLayout.CENTER);

        JLabel note = new JLabel("  Click + to configure a field / qualifier field. "
                + "Coverage over a few samples is a hint — a large-N probe is the authority.");
        note.setForeground(Color.GRAY);
        add(note, BorderLayout.SOUTH);
    }

    public void onConfigureField(BiConsumer<String, String> handler) {
        this.onConfigureField = handler == null ? (a, b) -> { } : handler;
    }

    public void onConfigureQualifier(BiConsumer<String, String> handler) {
        this.onConfigureQualifier = handler == null ? (a, b) -> { } : handler;
    }

    /** Push sample QIDs in (e.g. from the selected class) and load them. */
    public void showFor(List<String> qids) {
        qidsField.setText(String.join(" ", qids));
        load(qids);
    }

    private void load(List<String> qids) {
        if (qids.isEmpty()) {
            status.setText("  enter one or more QIDs");
            return;
        }
        status.setText("  loading " + qids.size() + " …");
        rowsHost.removeAll();
        rowsHost.revalidate();
        rowsHost.repaint();
        valuesArea.setText("");

        new SwingWorker<List<EntityStatementSummary>, Void>() {
            @Override protected List<EntityStatementSummary> doInBackground() {
                List<EntityStatementSummary> summaries = new ArrayList<>();
                for (String qid : qids) {
                    try {
                        summaries.add(EntityStatementSummary.fetch(qid, client));
                    } catch (Exception ignored) {
                        // skip a failed sample
                    }
                }
                return summaries;
            }
            @Override protected void done() {
                try {
                    List<EntityStatementSummary> summaries = get();
                    buildChips(summaries);
                    buildRows(MergedStatementSummary.of(summaries));
                    buildValues(summaries);
                    status.setText("  " + summaries.size() + " sample(s)");
                } catch (Exception ex) {
                    status.setText("  failed: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void buildChips(List<EntityStatementSummary> summaries) {
        chipsRow.removeAll();
        if (!summaries.isEmpty()) {
            chipsRow.add(new JLabel("samples:"));
        }
        for (EntityStatementSummary s : summaries) {
            chipsRow.add(qidLink(s.qid()));
        }
        chipsRow.revalidate();
        chipsRow.repaint();
    }

    private void buildRows(MergedStatementSummary merged) {
        rowsHost.removeAll();
        for (MergedStatementSummary.PropertyCoverage p : merged.properties()) {
            rowsHost.add(propertyRow(p, merged.sampleCount()));
            for (MergedStatementSummary.QualifierCoverage q : p.qualifiers()) {
                rowsHost.add(qualifierRow(q, p.totalStatements()));
            }
        }
        rowsHost.add(Box.createVerticalGlue());
        rowsHost.revalidate();
        rowsHost.repaint();
    }

    private void buildValues(List<EntityStatementSummary> summaries) {
        StringBuilder sb = new StringBuilder();
        for (EntityStatementSummary s : summaries) {
            sb.append(s.concise()).append('\n');
        }
        valuesArea.setText(sb.toString());
        valuesArea.setCaretPosition(0);
    }

    private JComponent propertyRow(MergedStatementSummary.PropertyCoverage p, int samples) {
        JPanel row = leftRow(0);
        row.add(plusButton("Configure a field for " + p.label() + " (" + p.pid() + ")",
                () -> onConfigureField.accept(p.pid(), p.label())));
        JLabel label = new JLabel(p.label() + "  (" + p.pid() + ")   —   "
                + p.entitiesWith() + "/" + samples + " entities, "
                + p.totalStatements() + " statements");
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        row.add(label);
        return row;
    }

    private JComponent qualifierRow(MergedStatementSummary.QualifierCoverage q, int total) {
        JPanel row = leftRow(28);
        row.add(plusButton("Configure a qualifier field for " + q.label() + " (" + q.pid() + ")",
                () -> onConfigureQualifier.accept(q.pid(), q.label())));
        int pct = total == 0 ? 0 : (100 * q.statementsWith()) / total;
        row.add(new JLabel("↳ " + q.label() + " (" + q.pid() + "): "
                + q.statementsWith() + "/" + total + "  (" + pct + "%)"));
        return row;
    }

    private static JPanel leftRow(int indent) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        row.setBorder(BorderFactory.createEmptyBorder(0, indent, 0, 0));
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height + 8));
        return row;
    }

    private static JButton plusButton(String tip, Runnable action) {
        JButton b = new JButton("+");
        b.setMargin(new Insets(0, 4, 0, 4));
        b.setToolTipText(tip);
        b.addActionListener(e -> action.run());
        return b;
    }

    /** A clickable QID that opens its wiki page. */
    private static JLabel qidLink(String qid) {
        JLabel link = new JLabel("<html><a href=''>" + qid + "</a></html>");
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setToolTipText("Open " + qid + " on wikidata.org");
        link.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                openInBrowser("https://www.wikidata.org/wiki/" + qid);
            }
        });
        return link;
    }

    private static void openInBrowser(String url) {
        try {
            Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception ignored) {
            // no browser available — best effort
        }
    }

    private static List<String> parseQids(String text) {
        List<String> out = new ArrayList<>();
        if (text != null) {
            for (String tok : text.trim().split("[\\s,]+")) {
                if (WikidataIds.isQid(tok)) {
                    out.add(tok);
                }
            }
        }
        return out;
    }

    /** Standalone launcher (clicks just report — no modelbuilder to wire to). */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            WikidataSparqlClient client =
                    new WikidataSparqlClient("quiz-statement-summary/1.0 (ggyepesi@gmail.com)");
            StatementSummaryPanel panel = new StatementSummaryPanel(client);
            panel.onConfigureField((pid, label) ->
                    panel.status.setText("  field: " + label + " (" + pid + ")"));
            panel.onConfigureQualifier((pid, label) ->
                    panel.status.setText("  qualifier field: " + label + " (" + pid + ")"));
            JFrame frame = new JFrame("Statement summary (example-first) — #91");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(panel);
            frame.setSize(820, 720);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            panel.showFor(Arrays.asList(args.length > 0 ? args : new String[]{"Q103474"}));
        });
    }
}
