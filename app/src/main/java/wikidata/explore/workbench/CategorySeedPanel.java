package wikidata.explore.workbench;

import wikidata.WikidataIds;

import wikidata.explore.query.logical.CategorySeedQuery;
import wikidata.explore.query.swing.SwingQueryRunner;
import wikidata.explore.wikiproject.WikiProjectArticle;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import workbench.EntityResultPanel;

/**
 * Pulls a plain Wikipedia content category's members and resolves them to QIDs
 * — for sets Wikidata under-models but Wikipedia curates (e.g. the 12 labours).
 * The QIDs feed the selected class's Seed QIDs. Shares {@link EntityResultPanel}
 * (filter + QID links + selection) with the WikiProject/Explore panels. (#40)
 */
public class CategorySeedPanel extends JPanel {

    private SwingQueryRunner queryRunner;

    private Consumer<List<String>> onAddSeedQids = qids -> {};
    private Consumer<List<String>> onReplaceSeedQids = qids -> {};
    private BiConsumer<String, String> onUseAsSourceQid = (qid, label) -> {};

    private final JTextField categoryField = new JTextField("Labours of Hercules", 24);
    private final JSpinner limitSpinner =
            new JSpinner(new SpinnerNumberModel(100, 1, 500, 10));
    private final JButton loadButton = new JButton("Load category");

    private final JButton addSelectedButton = new JButton("Add selected to Seed QIDs");
    private final JButton replaceSelectedButton =
            new JButton("Replace Seed QIDs with selected");
    private final JButton useSourceButton = new JButton("Use selected as class type (P31)");
    private final JLabel statusLabel = new JLabel(" ");

    private final EntityResultPanel results = new EntityResultPanel(
            List.of("Title", "QID", "Page ID"), 1, true);

    public CategorySeedPanel() {
        super(new BorderLayout(4, 4));
        buildUi();
    }

    /** One-shot: the load button binds to the first runner's workflow. */
    public void setQueryRunner(SwingQueryRunner queryRunner) {
        if (this.queryRunner != null || queryRunner == null) {
            return;
        }
        this.queryRunner = queryRunner;
        queryRunner.wireButton(
                loadButton, this::acceptResult, this::buildQuery,
                ex -> showError("Load category failed", ex));
    }

    public void onAddSeedQids(Consumer<List<String>> h) {
        this.onAddSeedQids = h == null ? qids -> {} : h;
    }
    public void onReplaceSeedQids(Consumer<List<String>> h) {
        this.onReplaceSeedQids = h == null ? qids -> {} : h;
    }
    public void onUseAsSourceQid(BiConsumer<String, String> h) {
        this.onUseAsSourceQid = h == null ? (q, l) -> {} : h;
    }

    private void buildUi() {
        results.setColumnWidths(320, 80, 70);

        JPanel configRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        configRow.add(new JLabel("Category:"));
        configRow.add(categoryField);
        configRow.add(new JLabel("Limit:"));
        configRow.add(limitSpinner);
        configRow.add(loadButton);

        addSelectedButton.setToolTipText("Add the selected entities to the "
                + "selected class's Seed QIDs (they become its instances).");
        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        actionRow.add(addSelectedButton);
        actionRow.add(replaceSelectedButton);
        actionRow.add(useSourceButton);
        actionRow.add(statusLabel);

        JLabel hint = new JLabel(
                "<html>A plain Wikipedia content category (with or without the "
                + "\"Category:\" prefix), e.g. <b>Labours of Hercules</b>, "
                + "<b>Twelve Olympians</b>. Its article members are resolved to "
                + "Wikidata QIDs — for sets Wikidata under-models. Select rows, "
                + "then add their QIDs to the selected class.</html>");
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC));

        JPanel top = new JPanel(new BorderLayout(4, 2));
        top.add(configRow, BorderLayout.NORTH);
        top.add(actionRow, BorderLayout.CENTER);
        top.add(hint, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(results, BorderLayout.CENTER);

        addSelectedButton.setEnabled(false);
        replaceSelectedButton.setEnabled(false);
        useSourceButton.setEnabled(false);
        results.onSelectionChanged(() -> {
            boolean any = !results.selectedQids().isEmpty();
            addSelectedButton.setEnabled(any);
            replaceSelectedButton.setEnabled(any);
            useSourceButton.setEnabled(any);
        });

        categoryField.addActionListener(e -> loadButton.doClick());
        addSelectedButton.addActionListener(e -> onAddSeedQids.accept(results.selectedQids()));
        replaceSelectedButton.addActionListener(e -> onReplaceSeedQids.accept(results.selectedQids()));
        useSourceButton.addActionListener(e -> {
            String qid = results.firstSelected(1);
            if (WikidataIds.isQid(qid)) {
                onUseAsSourceQid.accept(qid, results.firstSelected(0));
            }
        });
    }

    private CategorySeedQuery buildQuery() {
        String cat = categoryField.getText() == null ? "" : categoryField.getText().trim();
        if (cat.isBlank()) {
            statusLabel.setText("Category is blank.");
            return null;
        }
        statusLabel.setText("Loading…");
        results.setRows(List.of());
        return new CategorySeedQuery(cat, ((Number) limitSpinner.getValue()).intValue());
    }

    private void acceptResult(List<WikiProjectArticle> result) {
        List<WikiProjectArticle> rows = result == null ? List.of() : result;
        List<List<Object>> tableRows = new ArrayList<>();
        long resolved = 0;
        for (WikiProjectArticle a : rows) {
            if (a == null) {
                continue;
            }
            String qid = a.qid() == null ? "" : a.qid();
            if (WikidataIds.isQid(qid)) {
                resolved++;
            }
            tableRows.add(List.of(
                    a.title() == null ? "" : a.title(),
                    qid,
                    String.valueOf(a.pageId())));
        }
        final long res = resolved;
        SwingUtilities.invokeLater(() -> {
            results.setRows(tableRows);
            statusLabel.setText(tableRows.size() + " members; " + res + " resolved QIDs.");
        });
    }

    private void showError(String title, Throwable ex) {
        String msg = ex == null ? "Unknown error" : ex.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = String.valueOf(ex);
        }
        statusLabel.setText(title + ": " + msg);
        final String body = msg;
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                this, body, title, JOptionPane.ERROR_MESSAGE));
    }
}
