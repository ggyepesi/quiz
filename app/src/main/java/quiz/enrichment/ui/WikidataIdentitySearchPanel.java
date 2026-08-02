package quiz.enrichment.ui;

import quiz.enrichment.WikimediaEntityLookup;
import wikidata.explore.query.logical.ClassSearchQuery;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.query.swing.SwingQueryRunner;
import wikidata.explore.workbench.EntityResultPanel;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
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
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reusable Wikidata identity picker. It deliberately proposes candidates rather
 * than silently accepting the first name match: descriptions and QIDs let the
 * user resolve aliases and people sharing a name.
 */
public final class WikidataIdentitySearchPanel extends JPanel {

    private static final Pattern QID = Pattern.compile("Q\\d+");

    private final JTextField search = new JTextField(28);
    private final JButton searchButton = new JButton("Search Wikidata");
    private final JTextField qidField = new JTextField(14);
    private final JButton lookupButton = new JButton("Look up QID");
    private final JButton exploreButton = new JButton("Explore Wikidata…");
    private final JButton useButton = new JButton("Use selected entity");
    private final JLabel status = new JLabel(" ");
    private final EntityResultPanel candidates =
            new EntityResultPanel(List.of("QID", "Label", "Description"), 0, false);
    private final BiConsumer<String, String> onSelected;
    private final SwingQueryRunner runner;

    public WikidataIdentitySearchPanel(
            SwingQueryRunner runner, String initialName,
            BiConsumer<String, String> onSelected) {
        super(new BorderLayout(6, 6));
        this.runner = runner;
        this.onSelected = onSelected == null ? (qid, label) -> { } : onSelected;
        search.setText(initialName == null ? "" : initialName);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JPanel searchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        searchRow.add(new JLabel("Name or description:"));
        searchRow.add(search);
        searchRow.add(searchButton);
        top.add(searchRow);
        // Paste a QID you already found (e.g. via Explore) instead of re-searching — its
        // label is fetched so you confirm the right entity before using it.
        JPanel qidRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        qidRow.add(new JLabel("Or paste a QID / URL:"));
        qidRow.add(qidField);
        qidRow.add(lookupButton);
        qidRow.add(exploreButton);
        top.add(qidRow);
        top.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        add(top, BorderLayout.NORTH);

        qidField.addActionListener(e -> lookupButton.doClick());
        lookupButton.addActionListener(e -> lookupQid());
        exploreButton.addActionListener(e ->
                wikidata.explore.workbench.ExploreByExamplePanel.showPicker(
                        this, runner, search.getText(),
                        (qid, label) -> showLooked(qid, label, "Selected in Explore")));
        exploreButton.setEnabled(runner != null);

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

    /** Extract a Q-number from a raw paste — a bare QID, {@code wd:Q…}, or a Wikidata URL. */
    static String parseQid(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = QID.matcher(text.trim().toUpperCase(Locale.ROOT));
        return m.find() ? m.group() : null;
    }

    private void lookupQid() {
        String qid = parseQid(qidField.getText());
        if (qid == null) {
            status.setText("Enter a Q-number (e.g. Q458) or a Wikidata URL.");
            return;
        }
        candidates.setRows(List.of());
        useButton.setEnabled(false);
        status.setText("Looking up " + qid + "…");
        if (runner == null) {
            showLooked(qid, qid, "");   // no query runner: use the QID as-is
            return;
        }
        runner.runQuiet(new WikimediaEntityLookup().byQid(qid),
                entity -> SwingUtilities.invokeLater(() -> {
                    boolean found = entity != null
                            && (!(entity.label() == null || entity.label().isBlank())
                            || !(entity.description() == null || entity.description().isBlank())
                            || !entity.claims().isEmpty());
                    if (found) {
                        showLooked(qid, entity.label(), entity.description());
                    } else {
                        candidates.setRows(List.of());
                        useButton.setEnabled(false);
                        status.setText(qid + " was not found. It was not selected.");
                    }
                }),
                ex -> SwingUtilities.invokeLater(() -> {
                    candidates.setRows(List.of());
                    useButton.setEnabled(false);
                    status.setText("Could not verify " + qid + ". It was not selected.");
                }));
    }

    /** Show the looked-up entity as a single, pre-selected candidate to confirm before use. */
    private void showLooked(String qid, String label, String description) {
        boolean hasLabel = label != null && !label.isBlank();
        candidates.setRows(List.of(List.of(
                qid,
                hasLabel ? label : qid,
                description == null ? "" : description)));
        candidates.selectFirstRow();
        updateUseButton();
        status.setText(hasLabel
                ? "Found " + qid + ": " + label + " — Use selected entity to confirm."
                : "Label unavailable; " + qid + " will be used as-is.");
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
