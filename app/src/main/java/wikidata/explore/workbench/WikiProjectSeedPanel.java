package wikidata.explore.workbench;

import wikidata.WikidataIds;

import wikidata.explore.query.logical.WikiProjectSeedQuery;
import wikidata.explore.query.swing.SwingQueryRunner;
import wikidata.explore.wikiproject.WikiProjectArticle;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Finds curated seed pages from Wikipedia/WikiProject assessment categories,
 * resolves them to Wikidata QIDs, and lets the user add those QIDs to the
 * selected class (as its Seed QIDs, or one as its membership type). Shares the
 * {@link EntityResultPanel} (search + QID links + selection) with the Explore
 * panel.
 */
public class WikiProjectSeedPanel extends JPanel {

    private static final int DEFAULT_LIMIT = 80;
    private static final String[] DEFAULT_CLASSES = {"FA", "FL", "GA", "B", "C", "Start"};

    private SwingQueryRunner queryRunner;

    private Consumer<List<String>> onAddSeedQids = qids -> {};
    private Consumer<List<String>> onReplaceSeedQids = qids -> {};
    private BiConsumer<String, String> onUseAsSourceQid = (qid, label) -> {};

    private final JTextField projectField = new JTextField("Mythology", 14);
    private final JComboBox<String> importanceBox =
            new JComboBox<>(new String[] {"Any", "Top", "High", "Mid", "Low", "Unknown"});
    private final JTextField classesField =
            new JTextField(String.join(" ", DEFAULT_CLASSES), 18);
    private final JSpinner limitSpinner =
            new JSpinner(new SpinnerNumberModel(DEFAULT_LIMIT, 1, 500, 10));

    private final JButton loadButton = new JButton("Load WikiProject seeds");
    private final JButton addSelectedButton = new JButton("Add selected to Seed QIDs");
    private final JButton replaceSelectedButton =
            new JButton("Replace Seed QIDs with selected");
    private final JButton useSourceButton = new JButton("Use selected as class type (P31)");
    private final JLabel statusLabel = new JLabel(" ");

    // Title, QID (link), Page ID, Category, Assessment page.
    private final EntityResultPanel results = new EntityResultPanel(
            List.of("Title", "QID", "Page ID", "Category", "Assessment page"), 1, true);

    public WikiProjectSeedPanel() {
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
                loadButton, this::acceptSeeds, this::buildSeedQuery,
                ex -> showError("Load WikiProject seeds failed", ex));
    }

    public void onAddSeedQids(Consumer<List<String>> handler) {
        this.onAddSeedQids = handler == null ? qids -> {} : handler;
    }

    public void onReplaceSeedQids(Consumer<List<String>> handler) {
        this.onReplaceSeedQids = handler == null ? qids -> {} : handler;
    }

    public void onUseAsSourceQid(BiConsumer<String, String> handler) {
        this.onUseAsSourceQid = handler == null ? (q, l) -> {} : handler;
    }

    private void buildUi() {
        results.setColumnWidths(220, 70, 60, 300, 220);

        JPanel configRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        configRow.add(new JLabel("Project:"));
        configRow.add(projectField);
        configRow.add(new JLabel("Importance:"));
        configRow.add(importanceBox);
        configRow.add(new JLabel("Classes:"));
        configRow.add(classesField);
        configRow.add(new JLabel("Limit:"));
        configRow.add(limitSpinner);
        configRow.add(loadButton);

        useSourceButton.setToolTipText("Make the selected entity the selected "
                + "class's membership type (instance-of / P31).");
        addSelectedButton.setToolTipText("Add the selected entities to the "
                + "selected class's Seed QIDs (they become its instances).");

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        actionRow.add(addSelectedButton);
        actionRow.add(replaceSelectedButton);
        actionRow.add(useSourceButton);
        actionRow.add(statusLabel);

        JLabel hint = new JLabel(
                "<html>Curated Wikipedia assessment pages. Project = a WikiProject "
                + "name, e.g. <b>Mythology</b> or <b>Classical Greece and Rome</b> "
                + "(not \"Greek mythology\"). Importance <b>Any</b> uses the "
                + "class-only category (works for every project). Select rows, "
                + "then add their QIDs to the selected class.</html>");
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC));

        JPanel top = new JPanel(new BorderLayout(4, 2));
        top.add(configRow, BorderLayout.NORTH);
        top.add(actionRow, BorderLayout.CENTER);
        top.add(hint, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(results, BorderLayout.CENTER);

        // The actions operate on the selected rows — disable them until at least
        // one row with a resolved QID is selected.
        addSelectedButton.setEnabled(false);
        replaceSelectedButton.setEnabled(false);
        useSourceButton.setEnabled(false);
        results.onSelectionChanged(() -> {
            boolean any = !results.selectedQids().isEmpty();
            addSelectedButton.setEnabled(any);
            replaceSelectedButton.setEnabled(any);
            useSourceButton.setEnabled(any);
        });

        addSelectedButton.addActionListener(e -> onAddSeedQids.accept(results.selectedQids()));
        replaceSelectedButton.addActionListener(e -> onReplaceSeedQids.accept(results.selectedQids()));
        useSourceButton.addActionListener(e -> {
            String qid = results.firstSelected(1);
            if (WikidataIds.isQid(qid)) {
                onUseAsSourceQid.accept(qid, results.firstSelected(0));
            }
        });
    }

    private WikiProjectSeedQuery buildSeedQuery() {
        String project = projectField.getText().trim();
        String importance = String.valueOf(importanceBox.getSelectedItem()).trim();
        List<String> classes = parseClasses(classesField.getText());
        int limit = ((Number) limitSpinner.getValue()).intValue();

        if (project.isBlank()) {
            statusLabel.setText("Project is blank.");
            return null;
        }
        if (classes.isEmpty()) {
            statusLabel.setText("No classes.");
            return null;
        }

        statusLabel.setText("Loading WikiProject seeds...");
        results.setRows(List.of());
        return new WikiProjectSeedQuery(project, importance, classes, limit);
    }

    private void acceptSeeds(List<WikiProjectArticle> result) {
        List<WikiProjectArticle> rows = result == null ? List.of() : result;
        List<List<Object>> tableRows = new ArrayList<>();
        long resolved = 0;
        for (WikiProjectArticle a : rows) {
            if (a == null) {
                continue;
            }
            if (hasQid(a)) {
                resolved++;
            }
            tableRows.add(List.of(
                    nz(a.title()),
                    nz(a.qid()),
                    String.valueOf(a.pageId()),
                    nz(a.category()),
                    nz(a.assessmentPageTitle())));
        }
        final long res = resolved;
        SwingUtilities.invokeLater(() -> {
            results.setRows(tableRows);
            statusLabel.setText(tableRows.size() + " pages; " + res + " resolved QIDs.");
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

    private static boolean hasQid(WikiProjectArticle article) {
        return article != null && article.qid() != null
                && WikidataIds.isQid(article.qid());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static List<String> parseClasses(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String token : text.trim().split("[,;\\s]+")) {
            token = token.trim();
            if (!token.isBlank()) {
                out.add(token);
            }
        }
        return out;
    }
}
