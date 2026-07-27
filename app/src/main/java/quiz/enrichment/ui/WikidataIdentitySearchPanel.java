package quiz.enrichment.ui;

import wikidata.explore.query.logical.ClassSearchQuery;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.query.swing.SwingQueryRunner;
import wikidata.explore.workbench.EntityResultPanel;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Reusable Wikidata identity picker. It deliberately proposes candidates rather
 * than silently accepting the first name match: descriptions and QIDs let the
 * user resolve aliases and people sharing a name.
 */
public final class WikidataIdentitySearchPanel extends JPanel {

    private final JTextField search = new JTextField(28);
    private final JButton searchButton = new JButton("Search Wikidata");
    private final JButton useButton = new JButton("Use selected entity");
    private final JLabel status = new JLabel(" ");
    private final EntityResultPanel candidates =
            new EntityResultPanel(List.of("QID", "Label", "Description"), 0, false);
    private final BiConsumer<String, String> onSelected;

    public WikidataIdentitySearchPanel(
            SwingQueryRunner runner, String initialName,
            BiConsumer<String, String> onSelected) {
        super(new BorderLayout(6, 6));
        this.onSelected = onSelected == null ? (qid, label) -> { } : onSelected;
        search.setText(initialName == null ? "" : initialName);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        top.add(new JLabel("Name or description:"));
        top.add(search);
        top.add(searchButton);
        add(top, BorderLayout.NORTH);

        candidates.setColumnWidths(75, 190, 430);
        candidates.onSelectionChanged(this::updateUseButton);
        candidates.setPreferredSize(new Dimension(720, 300));
        add(candidates, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        useButton.setEnabled(false);
        useButton.addActionListener(e -> useSelected());
        bottom.add(useButton);
        bottom.add(status);
        add(bottom, BorderLayout.SOUTH);

        search.addActionListener(e -> searchButton.doClick());
        runner.wireButton(searchButton, this::acceptResults, this::query,
                ex -> SwingUtilities.invokeLater(() -> {
                    status.setText("Search failed.");
                    JOptionPane.showMessageDialog(this,
                            "Wikidata search failed: " + ex.getMessage(),
                            "Search failed", JOptionPane.ERROR_MESSAGE);
                }));
    }

    public void searchNow() {
        if (!search.getText().isBlank()) {
            searchButton.doClick();
        }
    }

    private ClassSearchQuery query() {
        String text = search.getText().trim();
        if (text.isBlank()) {
            status.setText("Enter a name or description.");
            return null;
        }
        candidates.setRows(List.of());
        useButton.setEnabled(false);
        status.setText("Searching…");
        return new ClassSearchQuery(text, ClassSearchQuery.Mode.API, 20);
    }

    private void acceptResults(TableQueryResult result) {
        SwingUtilities.invokeLater(() -> {
            List<List<Object>> rows = result == null ? List.of() : result.rows();
            candidates.setRows(rows);
            status.setText(rows.isEmpty()
                    ? "No matching entities."
                    : rows.size() + " candidate(s) — verify the description before selecting.");
            updateUseButton();
        });
    }

    private void updateUseButton() {
        useButton.setEnabled(candidates.hasSelection());
    }

    private void useSelected() {
        String qid = candidates.firstSelected(0);
        if (!qid.matches("Q\\d+")) {
            return;
        }
        onSelected.accept(qid, candidates.firstSelected(1));
    }

    public static void showDialog(
            Component parent, SwingQueryRunner runner, String initialName,
            BiConsumer<String, String> onSelected) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner, "Find Wikidata identity",
                JDialog.ModalityType.APPLICATION_MODAL);
        final WikidataIdentitySearchPanel[] holder = new WikidataIdentitySearchPanel[1];
        holder[0] = new WikidataIdentitySearchPanel(runner, initialName, (qid, label) -> {
            onSelected.accept(qid, label);
            dialog.dispose();
        });
        dialog.add(holder[0]);
        dialog.setMinimumSize(new Dimension(760, 430));
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        SwingUtilities.invokeLater(holder[0]::searchNow);
        dialog.setVisible(true);
    }
}
