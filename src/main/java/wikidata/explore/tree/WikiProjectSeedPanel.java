package wikidata.explore.tree;

import wikidata.WikidataSparqlClient;
import wikidata.explore.wikiproject.WikiProjectArticle;
import wikidata.explore.wikiproject.WikiProjectCategoryReader;
import wikidata.explore.wikiproject.WikiProjectMediaWikiClient;
import wikidata.explore.wikiproject.WikiProjectQidResolver;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Finds curated seed pages from Wikipedia/WikiProject assessment categories,
 * resolves them to Wikidata QIDs, and lets the user add those QIDs to the
 * selected RuleNode.
 */
public class WikiProjectSeedPanel extends JPanel {

    private static final int DEFAULT_LIMIT = 80;
    private static final String[] DEFAULT_CLASSES = {"FA", "FL", "GA", "B", "C", "Start"};

    private WikidataSparqlClient sparqlClient;

    private Consumer<List<WikiProjectArticle>> onAddIncludedQids = articles -> {};
    private Consumer<List<WikiProjectArticle>> onReplaceIncludedQids = articles -> {};
    private Consumer<WikiProjectArticle> onUseAsSourceQid = article -> {};

    private final JTextField projectField = new JTextField("Astronomy", 14);
    private final JComboBox<String> importanceBox =
            new JComboBox<>(new String[] {"Top", "High", "Mid", "Low", "Unknown"});
    private final JTextField classesField =
            new JTextField(String.join(" ", DEFAULT_CLASSES), 18);
    private final JSpinner limitSpinner =
            new JSpinner(new SpinnerNumberModel(DEFAULT_LIMIT, 1, 500, 10));

    private final JButton loadButton = new JButton("Load WikiProject seeds");
    private final JButton addSelectedButton = new JButton("Add selected to included QIDs");
    private final JButton replaceSelectedButton =
            new JButton("Replace included QIDs with selected");
    private final JButton useSourceButton = new JButton("Use selected as source QID");
    private final JLabel statusLabel = new JLabel(" ");

    private final List<WikiProjectArticle> articles = new ArrayList<>();
    private final ArticleTableModel tableModel = new ArticleTableModel(articles);
    private final JTable table = new JTable(tableModel);

    public WikiProjectSeedPanel() {
        super(new BorderLayout(4, 4));
        buildUi();
    }

    public void setSparqlClient(WikidataSparqlClient sparqlClient) {
        this.sparqlClient = sparqlClient;
    }

    public void onAddIncludedQids(Consumer<List<WikiProjectArticle>> handler) {
        this.onAddIncludedQids = handler == null ? articles -> {} : handler;
    }

    public void onReplaceIncludedQids(Consumer<List<WikiProjectArticle>> handler) {
        this.onReplaceIncludedQids = handler == null ? articles -> {} : handler;
    }

    public void onUseAsSourceQid(Consumer<WikiProjectArticle> handler) {
        this.onUseAsSourceQid = handler == null ? article -> {} : handler;
    }

    private void buildUi() {
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        table.getColumnModel().getColumn(ArticleTableModel.COL_USE_SOURCE)
             .setCellRenderer(new ButtonRenderer());
        table.getColumnModel().getColumn(ArticleTableModel.COL_USE_SOURCE)
             .setCellEditor(new ButtonEditor("Use source", row -> {
                 WikiProjectArticle article = articles.get(row);
                 if (hasQid(article)) {
                     onUseAsSourceQid.accept(article);
                 }
             }));

        table.getColumnModel().getColumn(ArticleTableModel.COL_TITLE).setPreferredWidth(220);
        table.getColumnModel().getColumn(ArticleTableModel.COL_QID).setPreferredWidth(70);
        table.getColumnModel().getColumn(ArticleTableModel.COL_PAGE_ID).setPreferredWidth(60);
        table.getColumnModel().getColumn(ArticleTableModel.COL_CATEGORY).setPreferredWidth(300);
        table.getColumnModel().getColumn(ArticleTableModel.COL_ASSESSMENT).setPreferredWidth(220);
        table.getColumnModel().getColumn(ArticleTableModel.COL_USE_SOURCE).setPreferredWidth(95);

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

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        actionRow.add(addSelectedButton);
        actionRow.add(replaceSelectedButton);
        actionRow.add(useSourceButton);
        actionRow.add(statusLabel);

        JLabel hint = new JLabel(
                "Curated Wikipedia assessment pages. Select rows and add their Wikidata QIDs to the current node.");
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC));

        JPanel top = new JPanel(new BorderLayout(4, 2));
        top.add(configRow, BorderLayout.NORTH);
        top.add(actionRow, BorderLayout.CENTER);
        top.add(hint, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);

        loadButton.addActionListener(e -> loadSeeds());
        addSelectedButton.addActionListener(e ->
                onAddIncludedQids.accept(selectedArticlesWithQids()));
        replaceSelectedButton.addActionListener(e ->
                onReplaceIncludedQids.accept(selectedArticlesWithQids()));
        useSourceButton.addActionListener(e -> {
            List<WikiProjectArticle> selected = selectedArticlesWithQids();
            if (!selected.isEmpty()) {
                onUseAsSourceQid.accept(selected.get(0));
            }
        });
    }

    private void loadSeeds() {
        String project = projectField.getText().trim();
        String importance = String.valueOf(importanceBox.getSelectedItem()).trim();
        List<String> classes = parseClasses(classesField.getText());
        int limit = ((Number) limitSpinner.getValue()).intValue();

        if (project.isBlank()) {
            statusLabel.setText("Project is blank.");
            return;
        }
        if (classes.isEmpty()) {
            statusLabel.setText("No classes.");
            return;
        }

        setBusy(true);
        statusLabel.setText("Loading WikiProject seeds...");
        articles.clear();
        tableModel.fireTableDataChanged();

        SwingWorker<List<WikiProjectArticle>, String> worker = new SwingWorker<>() {
            @Override
            protected List<WikiProjectArticle> doInBackground() throws Exception {
                List<WikiProjectArticle> loaded =
                        loadArticlesSync(project, importance, classes, limit);
                publish("Resolving Wikidata QIDs for " + loaded.size() + " pages...");
                attachQids(loaded);
                return loaded;
            }

            @Override
            protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) {
                    statusLabel.setText(chunks.get(chunks.size() - 1));
                }
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    List<WikiProjectArticle> result = get();
                    articles.clear();
                    articles.addAll(result);
                    tableModel.fireTableDataChanged();

                    long resolved = result.stream()
                                          .filter(WikiProjectSeedPanel::hasQid)
                                          .count();
                    statusLabel.setText(result.size() + " pages; "
                                                + resolved + " resolved QIDs.");
                } catch (Exception ex) {
                    statusLabel.setText("Error: " + ex.getMessage());
                    ex.printStackTrace();
                }
            }
        };

        worker.execute();
    }

    private void setBusy(boolean busy) {
        loadButton.setEnabled(!busy);
        addSelectedButton.setEnabled(!busy);
        replaceSelectedButton.setEnabled(!busy);
        useSourceButton.setEnabled(!busy);
        setCursor(Cursor.getPredefinedCursor(
                busy ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    private List<WikiProjectArticle> loadArticlesSync(
            String project,
            String importance,
            List<String> classes,
            int limit) throws Exception {

        WikiProjectMediaWikiClient mw = new WikiProjectMediaWikiClient();
        WikiProjectCategoryReader reader = new WikiProjectCategoryReader(mw);
        List<WikiProjectArticle> out = new ArrayList<>();

        for (String cls : classes) {
            if (out.size() >= limit) {
                break;
            }

            String category = "Category:" + cls + "-Class " + project
                    + " articles of " + importance + "-importance";
            int remaining = Math.max(1, limit - out.size());
            List<WikiProjectArticle> categoryArticles =
                    reader.categoryMembers(category, remaining);

            for (WikiProjectArticle article : categoryArticles) {
                if (article.title() == null || article.title().isBlank()) {
                    continue;
                }
                out.add(article);
                if (out.size() >= limit) {
                    break;
                }
            }
        }

        return dedupeByTitle(out);
    }

    private void attachQids(List<WikiProjectArticle> articles) throws Exception {
        if (sparqlClient == null || articles == null || articles.isEmpty()) {
            return;
        }
        new WikiProjectQidResolver(sparqlClient).attachQids(articles);
    }

    private List<WikiProjectArticle> selectedArticlesWithQids() {
        int[] rows = table.getSelectedRows();
        List<WikiProjectArticle> selected = new ArrayList<>();

        for (int viewRow : rows) {
            int modelRow = table.convertRowIndexToModel(viewRow);
            if (modelRow >= 0 && modelRow < articles.size()) {
                WikiProjectArticle article = articles.get(modelRow);
                if (hasQid(article)) {
                    selected.add(article);
                }
            }
        }
        return selected;
    }

    private static boolean hasQid(WikiProjectArticle article) {
        return article != null
                && article.qid() != null
                && article.qid().matches("Q\\d+");
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

    private static List<WikiProjectArticle> dedupeByTitle(List<WikiProjectArticle> in) {
        List<WikiProjectArticle> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (WikiProjectArticle article : in) {
            String key = article.title().toLowerCase();
            if (seen.add(key)) {
                out.add(article);
            }
        }
        return out;
    }

    static final class ArticleTableModel extends AbstractTableModel {
        static final int COL_TITLE = 0;
        static final int COL_QID = 1;
        static final int COL_PAGE_ID = 2;
        static final int COL_CATEGORY = 3;
        static final int COL_ASSESSMENT = 4;
        static final int COL_USE_SOURCE = 5;

        private static final String[] COLUMNS = {
                "Title", "QID", "Page ID", "Category", "Assessment page", "Source"
        };

        private final List<WikiProjectArticle> rows;

        ArticleTableModel(List<WikiProjectArticle> rows) {
            this.rows = rows;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int col) { return COLUMNS[col]; }
        @Override public Class<?> getColumnClass(int col) {
            return col == COL_USE_SOURCE ? JButton.class : String.class;
        }
        @Override public boolean isCellEditable(int row, int col) {
            return col == COL_USE_SOURCE;
        }

        @Override
        public Object getValueAt(int row, int col) {
            WikiProjectArticle article = rows.get(row);
            return switch (col) {
                case COL_TITLE -> article.title();
                case COL_QID -> article.qid();
                case COL_PAGE_ID -> String.valueOf(article.pageId());
                case COL_CATEGORY -> article.category();
                case COL_ASSESSMENT -> article.assessmentPageTitle();
                case COL_USE_SOURCE -> "Use source";
                default -> "";
            };
        }
    }

    private static class ButtonRenderer extends JButton
            implements javax.swing.table.TableCellRenderer {
        ButtonRenderer() { setOpaque(true); }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            setText(value == null ? "" : value.toString());
            return this;
        }
    }

    private static class ButtonEditor extends DefaultCellEditor {
        private final JButton button;
        private final java.util.function.IntConsumer action;
        private int currentRow;

        ButtonEditor(String label, java.util.function.IntConsumer action) {
            super(new JCheckBox());
            this.action = action;
            button = new JButton(label);
            button.addActionListener(e -> {
                fireEditingStopped();
                action.accept(currentRow);
            });
        }

        @Override
        public Component getTableCellEditorComponent(
                JTable table, Object value, boolean isSelected,
                int row, int column) {
            currentRow = row;
            return button;
        }

        @Override public Object getCellEditorValue() { return button.getText(); }
    }
}
