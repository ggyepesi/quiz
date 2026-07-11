package wikidata.explore.workbench;

import wikidata.WikidataSparqlClient;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Example-first statement view (GitHub #91, slice 3): enter or push in a few
 * sample QIDs and see the MERGED coverage — property → qualifiers, each badged
 * with how many samples/statements carry it — as <b>clickable rows</b>. A `+`
 * on a property configures a field for it; a `+` on a qualifier configures a
 * qualifier field (e.g. edition ← P805). The click sets the PID + name and opens
 * the field editor pre-filled, so "selecting a statement is almost enough."
 *
 * <p>Standalone-usable ({@link #main}) and integration-ready ({@link #showFor}
 * + the two configure callbacks the modelbuilder wires to its Add-Field path).
 * The coverage badge is a directional hint over a few samples, not the aggregate
 * verdict — the panel says so.
 */
public final class StatementSummaryPanel extends JPanel {

    private final WikidataSparqlClient client;
    private final JTextField qidsField = new JTextField(26);
    private final JLabel status = new JLabel(" ");
    private final JPanel rowsHost = new JPanel();

    // (pid, humanLabel) — a statement property, or a statement qualifier.
    private BiConsumer<String, String> onConfigureField = (pid, label) -> { };
    private BiConsumer<String, String> onConfigureQualifier = (pid, label) -> { };

    public StatementSummaryPanel(WikidataSparqlClient client) {
        this.client = client;
        setLayout(new BorderLayout(6, 6));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        top.add(new JLabel("Sample QIDs:"));
        top.add(qidsField);
        JButton load = new JButton("Load");
        load.addActionListener(e -> load(parseQids(qidsField.getText())));
        top.add(load);
        top.add(status);
        add(top, BorderLayout.NORTH);

        rowsHost.setLayout(new BoxLayout(rowsHost, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(rowsHost);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        JLabel note = new JLabel("  Click + to configure a field / qualifier field. "
                + "Coverage over a few samples is a hint — a large-N probe is the authority.");
        note.setForeground(Color.GRAY);
        add(note, BorderLayout.SOUTH);
    }

    /** Wire a statement-property click to the modelbuilder's Add-Field path. */
    public void onConfigureField(BiConsumer<String, String> handler) {
        this.onConfigureField = handler == null ? (a, b) -> { } : handler;
    }

    /** Wire a qualifier click to the modelbuilder's Add-qualifier-Field path. */
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

        new SwingWorker<MergedStatementSummary, Void>() {
            @Override protected MergedStatementSummary doInBackground() {
                List<EntityStatementSummary> summaries = new ArrayList<>();
                for (String qid : qids) {
                    try {
                        summaries.add(EntityStatementSummary.fetch(qid, client));
                    } catch (Exception ignored) {
                        // skip a failed sample
                    }
                }
                return MergedStatementSummary.of(summaries);
            }
            @Override protected void done() {
                try {
                    buildRows(get());
                    status.setText("  " + qids.size() + " sample(s)");
                } catch (Exception ex) {
                    status.setText("  failed: " + ex.getMessage());
                }
            }
        }.execute();
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

    private static List<String> parseQids(String text) {
        List<String> out = new ArrayList<>();
        if (text != null) {
            for (String tok : text.trim().split("[\\s,]+")) {
                if (tok.matches("Q\\d+")) {
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
            frame.setSize(780, 720);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            panel.showFor(Arrays.asList(args.length > 0 ? args : new String[]{"Q103474"}));
        });
    }
}
