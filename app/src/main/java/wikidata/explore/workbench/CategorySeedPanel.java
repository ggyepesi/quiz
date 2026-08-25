package wikidata.explore.workbench;

import objectview.view.ViewableListPanel;
import wikidata.WikidataIds;
import wikidata.explore.query.logical.CategoryBrowseQuery;
import wikidata.explore.query.swing.SwingQueryRunner;
import wikidata.explore.wikiproject.WikiProjectArticle;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Browses one Wikipedia category and lets its resolved article QIDs seed a class. */
public class CategorySeedPanel extends JPanel {
    private SwingQueryRunner queryRunner;
    private Consumer<List<String>> onAddSeedQids = qids -> {};
    private Consumer<List<String>> onReplaceSeedQids = qids -> {};
    private BiConsumer<String, String> onUseAsSourceQid = (qid, label) -> {};

    private final JTextField categoryField = new JTextField("Labours of Hercules", 24);
    private final JSpinner limitSpinner =
            new JSpinner(new SpinnerNumberModel(100, 1, 500, 10));
    private final JButton loadButton = new JButton("Load category");
    private final JButton backButton = new JButton("◀ Back");
    private final JButton openParentButton = new JButton("Open parent");
    private final JButton openSubcategoryButton = new JButton("Open subcategory");
    private final JButton addSelectedButton = new JButton("Add selected to Seed QIDs");
    private final JButton replaceSelectedButton =
            new JButton("Replace Seed QIDs with selected");
    private final JButton useSourceButton = new JButton("Use selected as class type (P31)");
    private final JLabel statusLabel = new JLabel(" ");

    private final javax.swing.border.TitledBorder parentBorder =
            BorderFactory.createTitledBorder("Parent categories (0)");
    private final javax.swing.border.TitledBorder subcategoryBorder =
            BorderFactory.createTitledBorder("Subcategories (0)");
    private final javax.swing.border.TitledBorder articleBorder =
            BorderFactory.createTitledBorder("Articles (0)");

    private final ViewableListPanel parents =
            new ViewableListPanel(WikipediaPageView.class, "No parent categories.");
    private final ViewableListPanel subcategories =
            new ViewableListPanel(WikipediaPageView.class, "No subcategories.");
    private final ViewableListPanel articles =
            new ViewableListPanel(WikipediaPageView.class, "No articles.");
    private WikipediaPageView selectedParent;
    private WikipediaPageView selectedSubcategory;
    private List<WikipediaPageView> selectedArticles = List.of();
    private final BrowseHistory history = new BrowseHistory();

    public CategorySeedPanel() {
        super(new BorderLayout(4, 4));
        buildUi();
    }

    public void setQueryRunner(SwingQueryRunner queryRunner) {
        if (this.queryRunner != null || queryRunner == null) return;
        this.queryRunner = queryRunner;
        queryRunner.wireButton(loadButton, this::acceptResult, this::buildQuery, ex -> {
            history.abandoned();
            showError("Load category failed", ex);
        });
    }

    public void onAddSeedQids(Consumer<List<String>> handler) {
        onAddSeedQids = handler == null ? qids -> {} : handler;
    }
    public void onReplaceSeedQids(Consumer<List<String>> handler) {
        onReplaceSeedQids = handler == null ? qids -> {} : handler;
    }
    public void onUseAsSourceQid(BiConsumer<String, String> handler) {
        onUseAsSourceQid = handler == null ? (qid, label) -> {} : handler;
    }

    private void buildUi() {
        configurePageView(parents);
        configurePageView(subcategories);
        configurePageView(articles);

        JPanel config = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        config.add(new JLabel("Category:")); config.add(categoryField);
        config.add(new JLabel("Limit:")); config.add(limitSpinner);
        config.add(loadButton); config.add(backButton);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        actions.add(addSelectedButton); actions.add(replaceSelectedButton);
        actions.add(useSourceButton); actions.add(statusLabel);

        JLabel hint = new JLabel("<html>Browse a Wikipedia category. Parent categories, "
                + "subcategories and articles are the same kind of page card; category "
                + "cards navigate, while selected article QIDs can seed the class.</html>");
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC));
        JPanel top = new JPanel(new BorderLayout(4, 2));
        top.add(config, BorderLayout.NORTH); top.add(actions, BorderLayout.CENTER);
        top.add(hint, BorderLayout.SOUTH);

        JPanel parentPanel = pagePanel(parentBorder, parents, openParentButton);
        JPanel childPanel = pagePanel(subcategoryBorder, subcategories,
                openSubcategoryButton);
        parentPanel.setMinimumSize(new Dimension(0, 0));
        childPanel.setMinimumSize(new Dimension(0, 0));
        JTabbedPane relationships = new JTabbedPane();
        relationships.addTab("Parents", parentPanel);
        relationships.addTab("Subcategories", childPanel);
        relationships.setMinimumSize(new Dimension(0, 0));

        JPanel articlePanel = new JPanel(new BorderLayout());
        articlePanel.setBorder(articleBorder);
        articlePanel.add(articles, BorderLayout.CENTER);
        articlePanel.setMinimumSize(new Dimension(0, 0));
        JSplitPane browser = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT, relationships, articlePanel);
        browser.setContinuousLayout(true);
        browser.setResizeWeight(0.42);
        browser.setDividerLocation(300);
        browser.setOneTouchExpandable(true);

        add(top, BorderLayout.NORTH);
        add(browser, BorderLayout.CENTER);
        SwingUtilities.invokeLater(() -> {
            if (browser.getHeight() > 0) browser.setDividerLocation(0.42);
        });

        addSelectedButton.setEnabled(false);
        replaceSelectedButton.setEnabled(false);
        useSourceButton.setEnabled(false);
        backButton.setEnabled(false);
        openParentButton.setEnabled(false);
        openSubcategoryButton.setEnabled(false);

        categoryField.addActionListener(event -> loadButton.doClick());
        parents.onSelectionChanged(selected -> {
            selectedParent = selected instanceof WikipediaPageView page ? page : null;
            openParentButton.setEnabled(selectedParent != null);
        });
        subcategories.onSelectionChanged(selected -> {
            selectedSubcategory = selected instanceof WikipediaPageView page ? page : null;
            openSubcategoryButton.setEnabled(selectedSubcategory != null);
        });
        parents.onActivated(selected -> {
            if (selected instanceof WikipediaPageView page) navigate(page);
        });
        subcategories.onActivated(selected -> {
            if (selected instanceof WikipediaPageView page) navigate(page);
        });
        articles.onSelectionSetChanged(selected -> {
            selectedArticles = selected.stream()
                    .filter(WikipediaPageView.class::isInstance)
                    .map(WikipediaPageView.class::cast).toList();
            updateArticleActions();
        });

        openParentButton.addActionListener(event -> navigate(selectedParent));
        openSubcategoryButton.addActionListener(event -> navigate(selectedSubcategory));
        backButton.addActionListener(event -> {
            if (!history.canGoBack()) return;
            history.goingBack();
            categoryField.setText(stripCategory(history.back()));
            loadButton.doClick();
        });
        addSelectedButton.addActionListener(event -> onAddSeedQids.accept(selectedQids()));
        replaceSelectedButton.addActionListener(
                event -> onReplaceSeedQids.accept(selectedQids()));
        useSourceButton.addActionListener(event -> {
            WikipediaPageView selected = selectedArticles.stream()
                    .filter(page -> WikidataIds.isQid(page.qid())).findFirst().orElse(null);
            if (selected != null) {
                onUseAsSourceQid.accept(selected.qid(), selected.getDisplayName());
            }
        });
    }

    private CategoryBrowseQuery buildQuery() {
        String category = categoryField.getText() == null
                ? "" : categoryField.getText().trim();
        if (category.isBlank()) {
            statusLabel.setText("Category is blank.");
            return null;
        }
        statusLabel.setText("Loading…");
        articles.setViewables(List.of());
        return new CategoryBrowseQuery(
                category, ((Number) limitSpinner.getValue()).intValue());
    }

    private void acceptResult(CategoryBrowseQuery.Result result) {
        List<WikiProjectArticle> parentPages = result == null ? List.of() : result.parents();
        List<WikiProjectArticle> childPages =
                result == null ? List.of() : result.subcategories();
        List<WikiProjectArticle> articlePages =
                result == null ? List.of() : result.articles();
        long resolved = articlePages.stream()
                .filter(page -> WikidataIds.isQid(page.qid())).count();
        SwingUtilities.invokeLater(() -> {
            history.arrived(result == null ? "" : result.category());
            categoryField.setText(stripCategory(history.current()));
            parentBorder.setTitle("Parent categories (" + parentPages.size() + ")");
            subcategoryBorder.setTitle("Subcategories (" + childPages.size() + ")");
            articleBorder.setTitle("Articles (" + articlePages.size() + ")");
            parents.setViewables(pageViews(parentPages, "parent"));
            subcategories.setViewables(pageViews(childPages, "subcategory"));
            articles.setViewables(pageViews(articlePages, "article"));
            backButton.setEnabled(history.canGoBack());
            statusLabel.setText(parentPages.size() + " parent(s), " + childPages.size()
                    + " subcategories, " + articlePages.size() + " articles; "
                    + resolved + " resolved QIDs.");
            revalidate(); repaint();
        });
    }

    private static JPanel pagePanel(javax.swing.border.TitledBorder border,
            JComponent pages, JButton open) {
        JPanel panel = new JPanel(new BorderLayout(4, 2));
        panel.setBorder(border); panel.add(pages, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        actions.add(open); panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private void navigate(WikipediaPageView page) {
        if (page == null || page.title().isBlank()) return;
        history.goingForward();
        categoryField.setText(stripCategory(page.title()));
        loadButton.doClick();
    }

    private static List<WikipediaPageView> pageViews(
            List<WikiProjectArticle> pages, String relationship) {
        return (pages == null ? List.<WikiProjectArticle>of() : pages).stream()
                .filter(Objects::nonNull)
                .map(page -> new WikipediaPageView(page, relationship)).toList();
    }

    private static void configurePageView(ViewableListPanel panel) {
        panel.hiddenFields(java.util.Set.of("title"));
        panel.valueLinker(wikidata.ui.WikidataLinks.valueLinker());
        // This browser has three compact instance surfaces; give their cards the
        // space initially and let the reader expand controls only where needed.
        panel.setControlsExpanded(false);
    }

    private List<String> selectedQids() {
        return selectedArticles.stream().map(WikipediaPageView::qid)
                .filter(WikidataIds::isQid).distinct().toList();
    }

    private void updateArticleActions() {
        boolean any = !selectedQids().isEmpty();
        addSelectedButton.setEnabled(any);
        replaceSelectedButton.setEnabled(any);
        useSourceButton.setEnabled(any);
    }

    private static String stripCategory(String value) {
        if (value == null) return "";
        return value.regionMatches(true, 0, "Category:", 0, 9)
                ? value.substring(9) : value;
    }

    private void showError(String title, Throwable failure) {
        String message = failure == null ? "Unknown error" : failure.getMessage();
        if (message == null || message.isBlank()) message = String.valueOf(failure);
        statusLabel.setText(title + ": " + message);
        String body = message;
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                this, body, title, JOptionPane.ERROR_MESSAGE));
    }
}
