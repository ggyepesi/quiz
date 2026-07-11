package wikidata.explore.workbench;

import wikidata.WikidataSparqlClient;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Read-only example-first statement view (GitHub #91, slice 2): enter a few
 * sample QIDs (or push them in from a class), and see each entity's statements —
 * property → values with qualifiers nested — plus a MERGED coverage view with
 * badges (property on X/N entities; each qualifier on M of that property's
 * statements). The coverage number is the reliability hint that makes qualifier
 * fields (year P585, edition P805) obvious and flags the gaps.
 *
 * <p>Standalone-usable (QID input) and integration-ready ({@link #showFor}), so
 * the modelbuilder can feed it the selected class's sample instances.
 *
 * <p>Note shown in the UI: a few samples is a directional hint, not the verdict —
 * a large-N coverage probe is the authority.
 */
public final class StatementSummaryPanel extends JPanel {

    private final WikidataSparqlClient client;
    private final JTextField qidsField = new JTextField(28);
    private final JTextArea output = new JTextArea();
    private final JLabel status = new JLabel(" ");

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

        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        add(new JScrollPane(output), BorderLayout.CENTER);

        add(new JLabel("  Coverage over a few samples is a directional hint — "
                + "a large-N probe is the authority."), BorderLayout.SOUTH);
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
        output.setText("");
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() {
                StringBuilder sb = new StringBuilder();
                List<EntityStatementSummary> summaries = new ArrayList<>();
                for (String qid : qids) {
                    sb.append("================ ").append(qid).append(" ================\n");
                    try {
                        EntityStatementSummary s = EntityStatementSummary.fetch(qid, client);
                        summaries.add(s);
                        sb.append(s.concise());
                    } catch (Exception ex) {
                        sb.append("  failed: ").append(ex.getMessage()).append('\n');
                    }
                }
                if (summaries.size() > 1) {
                    sb.append("\n================ MERGED COVERAGE ================\n");
                    sb.append(MergedStatementSummary.of(summaries).concise());
                }
                return sb.toString();
            }
            @Override protected void done() {
                try {
                    output.setText(get());
                    output.setCaretPosition(0);
                    status.setText("  " + qids.size() + " loaded");
                } catch (Exception ex) {
                    status.setText("  failed: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private static List<String> parseQids(String text) {
        List<String> out = new ArrayList<>();
        if (text != null) {
            for (String tok : Arrays.asList(text.trim().split("[\\s,]+"))) {
                if (tok.matches("Q\\d+")) {
                    out.add(tok);
                }
            }
        }
        return out;
    }

    /** Standalone launcher. */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            WikidataSparqlClient client =
                    new WikidataSparqlClient("quiz-statement-summary/1.0 (ggyepesi@gmail.com)");
            StatementSummaryPanel panel = new StatementSummaryPanel(client);
            JFrame frame = new JFrame("Statement summary (example-first) — #91");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(panel);
            frame.setSize(760, 720);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            if (args.length > 0) {
                panel.showFor(Arrays.asList(args));
            } else {
                panel.qidsField.setText("Q103474");
            }
        });
    }
}
