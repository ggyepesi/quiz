package wikipedia.discovery;

import domain.DomainModel;
import objectview.Viewable;
import objectview.render.RenderingMode;
import objectview.view.SearchableView;
import quiz.source.WikipediaSource;
import quiz.transform.ui.TransformController;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.query.swing.SwingQueryRunner;
import wikidata.explore.query.swing.SwingQuerySession;
import work.QueryContext;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Read-only source workbench over a saved domain. ModelBuilder/Transform own the model
 * and its instances; this panel selects a bounded set and compares their retained or
 * freshly fetched Wikipedia source documents.
 */
public final class WikipediaDiscoveryPanel extends JPanel implements AutoCloseable {
    private static final int DEFAULT_SAMPLE = 12;

    private final TransformController domain;
    private final SwingQuerySession queries = new SwingQuerySession(new QueryContext());
    private final SwingQueryRunner runner = queries.runner();

    private final JComboBox<String> type = new JComboBox<>();
    private final JSpinner sampleSize = new JSpinner(new SpinnerNumberModel(
            DEFAULT_SAMPLE, 1, WikipediaDiscoveryQuery.MAX_ARTICLES, 1));
    private final JButton randomSample = new JButton("Start random sample");
    private final JButton addSelection = new JButton("Add selection");
    private final JButton removeSelection = new JButton("Remove selection");
    private final JButton retained = new JButton("Analyze retained articles");
    private final JButton fetch = new JButton("Fetch selected articles");
    private final JButton cancel = new JButton("Cancel");
    private final JButton logs = new JButton("Query logs");
    private final JLabel sourceStatus = new JLabel(" ");
    private final JLabel resultStatus = new JLabel(
            "Select instances, then analyze retained articles or fetch them from Wikipedia.");

    private final JPanel instancesHolder = new JPanel(new BorderLayout());
    private final JPanel selectedHolder = new JPanel(new BorderLayout());
    private final JTabbedPane sourceTabs = new JTabbedPane();
    private final JTabbedPane resultTabs = new JTabbedPane();
    private final LinkedHashMap<String, WikidataDynamicObject> selected =
            new LinkedHashMap<>();
    private List<WikidataDynamicObject> classMembers = List.of();
    private List<WikidataDynamicObject> selectedInClass = List.of();
    private List<WikidataDynamicObject> selectedInSample = List.of();
    private SearchableView instancesView;
    private SearchableView selectedView;
    private boolean closed;

    public WikipediaDiscoveryPanel(DomainModel model) {
        super(new BorderLayout(6, 6));
        this.domain = new TransformController(model, null);
        build();
        wire();
        populateTypes();
    }

    private void build() {
        JPanel controls = new JPanel(new objectview.utils.swing.WrapLayout(
                FlowLayout.LEFT, 6, 2));
        controls.add(new JLabel("Class:"));
        controls.add(type);
        controls.add(new JLabel("Random starting sample:"));
        controls.add(sampleSize);
        controls.add(randomSample);
        controls.add(retained);
        controls.add(fetch);
        controls.add(cancel);
        controls.add(logs);

        JPanel north = new JPanel(new BorderLayout(4, 3));
        north.add(controls, BorderLayout.NORTH);
        north.add(sourceStatus, BorderLayout.SOUTH);

        JPanel instances = new JPanel(new BorderLayout(4, 4));
        JPanel instanceActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        instanceActions.add(addSelection);
        instances.add(instanceActions, BorderLayout.NORTH);
        instances.add(instancesHolder, BorderLayout.CENTER);
        sourceTabs.addTab("Class instances", instances);

        JPanel sample = new JPanel(new BorderLayout(4, 4));
        JPanel sampleActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        sampleActions.add(removeSelection);
        sample.add(sampleActions, BorderLayout.NORTH);
        sample.add(selectedHolder, BorderLayout.CENTER);
        sourceTabs.addTab("Selected articles — 0", sample);

        JPanel results = new JPanel(new BorderLayout(4, 4));
        results.add(resultStatus, BorderLayout.NORTH);
        results.add(resultTabs, BorderLayout.CENTER);
        emptyResults();

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                sourceTabs, results);
        split.setResizeWeight(0.42);
        split.setContinuousLayout(true);
        north.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        split.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
        add(north, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
    }

    private void wire() {
        type.addActionListener(event -> showType((String) type.getSelectedItem()));
        randomSample.addActionListener(event -> startRandomSample());
        addSelection.addActionListener(event -> addSelectedInstances());
        removeSelection.addActionListener(event -> removeSelectedInstances());
        retained.addActionListener(event -> analyzeRetained());
        fetch.addActionListener(event -> fetchSelected());
        logs.addActionListener(event -> queries.showLogs(this));
        runner.registerRunButton(fetch);
        runner.registerCancelButton(cancel);
        runner.onRunningChanged(running -> {
            updateActions();
            if (running) {
                sourceStatus.setText("Fetching exactly " + selected.size()
                        + " selected structured article(s), each with its infobox in the "
                        + "same request.");
            }
        });
    }

    private void populateTypes() {
        for (String candidate : domain.types()) {
            if (TransformController.ALL_ENTITIES.equals(candidate)) continue;
            if (wikipediaMembers(candidate).isEmpty()) continue;
            type.addItem(candidate);
        }
        if (type.getItemCount() == 0) {
            sourceStatus.setText("This project has no class instances with an English "
                    + "Wikipedia article correspondence.");
            updateActions();
        } else {
            type.setSelectedIndex(0);
            showType((String) type.getSelectedItem());
        }
    }

    private void showType(String className) {
        classMembers = className == null ? List.of() : wikipediaMembers(className);
        selected.clear();
        selectedInClass = List.of();
        selectedInSample = List.of();
        rebuildInstanceView();
        rebuildSelectedView();
        sourceStatus.setText(classMembers.size() + " " + className
                + " instance(s) have an English Wikipedia article correspondence. "
                + "No article will be fetched until Fetch selected articles is pressed.");
        updateActions();
    }

    private List<WikidataDynamicObject> wikipediaMembers(String className) {
        return domain.domain().instancesOf(className).stream()
                .filter(WikidataDynamicObject.class::isInstance)
                .map(WikidataDynamicObject.class::cast)
                .filter(value -> !value.enwikiTitle().isBlank())
                .distinct().toList();
    }

    private void rebuildInstanceView() {
        String className = (String) type.getSelectedItem();
        instancesView = instanceView(classMembers, className,
                values -> selectedInClass = dynamic(values));
        instancesHolder.removeAll();
        instancesHolder.add(instancesView, BorderLayout.CENTER);
        instancesHolder.revalidate();
        instancesHolder.repaint();
    }

    private void rebuildSelectedView() {
        String className = (String) type.getSelectedItem();
        selectedView = instanceView(new ArrayList<>(selected.values()), className,
                values -> selectedInSample = dynamic(values));
        selectedHolder.removeAll();
        selectedHolder.add(selectedView, BorderLayout.CENTER);
        selectedHolder.revalidate();
        selectedHolder.repaint();
        updateSelectedTab();
    }

    private SearchableView instanceView(List<? extends Viewable> values, String className,
            java.util.function.Consumer<List<Viewable>> selection) {
        return SearchableView.builder(values)
                .sample(className == null ? null : domain.configSample(className))
                .hiddenFields(className == null ? java.util.Set.of()
                        : domain.structuralFields(className))
                .fieldTypes(className == null ? null : domain.fieldTypes(className))
                .fieldSchemas(value -> domain.renderedFieldSchema(value, className))
                .typeSchemas(domain::fieldSchema)
                .valueLinker(wikidata.ui.WikidataLinks.valueLinker())
                .selectionSetListener(chosen -> selection.accept(chosen.stream()
                        .filter(Viewable.class::isInstance).map(Viewable.class::cast).toList()))
                .collapsible(true)
                .build();
    }

    private void startRandomSample() {
        List<WikidataDynamicObject> shuffled = new ArrayList<>(classMembers);
        java.util.Collections.shuffle(shuffled);
        int count = Math.min((Integer) sampleSize.getValue(), shuffled.size());
        selected.clear();
        shuffled.stream().limit(count).forEach(this::putSelected);
        selectedInSample = List.of();
        rebuildSelectedView();
        sourceTabs.setSelectedIndex(1);
        sourceStatus.setText("Selected " + count + " random " + type.getSelectedItem()
                + " instance(s). Add or remove instances before fetching.");
        updateActions();
    }

    private void addSelectedInstances() {
        int available = Math.max(0, WikipediaDiscoveryQuery.MAX_ARTICLES - selected.size());
        List<WikidataDynamicObject> candidates = selectedInClass.stream()
                .filter(value -> !selected.containsKey(key(value))).toList();
        List<WikidataDynamicObject> added = candidates.stream().limit(available).toList();
        added.forEach(this::putSelected);
        if (selectedView == null) {
            rebuildSelectedView();
        } else {
            selectedView.appendViewables(added);
            updateSelectedTab();
        }
        sourceStatus.setText("Added " + added.size() + " instance(s); " + selected.size()
                + " article(s) are now selected."
                + (added.size() == candidates.size() ? "" : " The remaining "
                + (candidates.size() - added.size()) + " instance(s) were not added because "
                + "one discovery run is bounded to "
                + WikipediaDiscoveryQuery.MAX_ARTICLES + " articles."));
        updateActions();
    }

    private void removeSelectedInstances() {
        List<WikidataDynamicObject> removed = List.copyOf(selectedInSample);
        removed.forEach(value -> selected.remove(key(value)));
        if (selectedView != null) selectedView.removeViewables(removed);
        selectedInSample = List.of();
        updateSelectedTab();
        sourceStatus.setText("Removed " + removed.size() + " instance(s); " + selected.size()
                + " article(s) remain selected.");
        updateActions();
    }

    private void putSelected(WikidataDynamicObject value) {
        selected.putIfAbsent(key(value), value);
    }

    private static String key(WikidataDynamicObject value) {
        return value.identityTypeName() + ':' + value.getIdentifier();
    }

    private void updateSelectedTab() {
        sourceTabs.setTitleAt(1, "Selected articles — " + selected.size());
    }

    private void analyzeRetained() {
        List<WikipediaDiscoveryQuery.Article> values = WikipediaDiscoveryQuery.retained(
                new ArrayList<>(selected.values()));
        showAnalysis(WikipediaStructureAnalysis.analyze(values), "retained");
    }

    private void fetchSelected() {
        List<WikidataDynamicObject> values = new ArrayList<>(selected.values());
        if (values.isEmpty()) return;
        sourceStatus.setText("Fetching exactly " + values.size()
                + " selected structured article(s), each with its infobox in the "
                + "same request.");
        runner.run(new WikipediaDiscoveryQuery(values), result -> SwingUtilities.invokeLater(() -> {
            showAnalysis(WikipediaStructureAnalysis.analyze(result), "fetched");
            sourceStatus.setText("Fetched and compared " + result.stream()
                    .filter(WikipediaDiscoveryQuery.Article::available).count() + " of "
                    + result.size() + " selected article(s). Query logs contain every request.");
        }), failure -> SwingUtilities.invokeLater(() -> {
            String message = failure == null || failure.getMessage() == null
                    ? "Wikipedia discovery failed." : failure.getMessage();
            sourceStatus.setText("Wikipedia discovery failed: " + message);
            JOptionPane.showMessageDialog(this, message,
                    "Wikipedia discovery failed", JOptionPane.ERROR_MESSAGE);
        }));
    }

    private void showAnalysis(WikipediaStructureAnalysis.Result analysis, String source) {
        resultTabs.removeAll();
        addResultTab("Articles", analysis.articles(), WikipediaSource.class,
                RenderingMode.CARD, "No structured articles are available.");
        addResultTab("Sections", analysis.sections(),
                WikipediaDiscoveryRows.SectionPattern.class, RenderingMode.CARD,
                "No section headings were retained.");
        addResultTab("Infobox values", analysis.infoboxes(),
                WikipediaDiscoveryRows.InfoboxPattern.class, RenderingMode.CARD,
                "No infobox values were retained.");
        addResultTab("Categories", analysis.categories(),
                WikipediaDiscoveryRows.CategoryPattern.class, RenderingMode.CARD,
                "No visible categories were retained.");
        addResultTab("Links by section", analysis.links(),
                WikipediaDiscoveryRows.LinkPattern.class, RenderingMode.CARD,
                "No article links were retained.");
        if (!analysis.unavailable().isEmpty()) {
            addResultTab("Problems", analysis.unavailable(),
                    WikipediaDiscoveryRows.UnavailableArticle.class, RenderingMode.CARD,
                    "Every selected article was available.");
        }
        resultStatus.setText("Compared " + analysis.availableArticles() + " of "
                + analysis.selectedArticles() + " selected " + source + " article(s): "
                + analysis.sections().size() + " section heading(s), "
                + analysis.infoboxes().size() + " infobox key(s), "
                + analysis.categories().size() + " category title(s), and "
                + analysis.links().size() + " section/link pattern(s).");
    }

    private <T extends Viewable> void addResultTab(String title, List<T> values,
            Class<T> rowType, RenderingMode mode, String emptyMessage) {
        SearchableView view = SearchableView.builder(values)
                .type(rowType)
                .mode(mode)
                .valueLinker(wikidata.ui.WikidataLinks.valueLinker())
                .emptyMessage(emptyMessage)
                .collapsible(true)
                .build();
        resultTabs.addTab(title + " — " + values.size(), view);
    }

    private void emptyResults() {
        resultTabs.removeAll();
        resultTabs.addTab("Discovery results", new JLabel(
                "  No articles have been analyzed yet."));
    }

    private void updateActions() {
        boolean running = runner.isRunning();
        boolean hasType = type.getSelectedItem() != null && !classMembers.isEmpty();
        boolean hasSelection = !selected.isEmpty();
        type.setEnabled(!running && type.getItemCount() > 0);
        sampleSize.setEnabled(!running && hasType);
        randomSample.setEnabled(!running && hasType);
        addSelection.setEnabled(!running && !selectedInClass.isEmpty()
                && selected.size() < WikipediaDiscoveryQuery.MAX_ARTICLES);
        removeSelection.setEnabled(!running && !selectedInSample.isEmpty());
        retained.setEnabled(!running && hasSelection);
        fetch.setEnabled(!running && hasSelection
                && selected.size() <= WikipediaDiscoveryQuery.MAX_ARTICLES);
        fetch.setText(running ? "Fetching selected articles…"
                : "Fetch " + selected.size() + " selected article"
                        + (selected.size() == 1 ? "" : "s"));
    }

    private static List<WikidataDynamicObject> dynamic(List<Viewable> values) {
        return values.stream().filter(WikidataDynamicObject.class::isInstance)
                .map(WikidataDynamicObject.class::cast).toList();
    }

    public boolean requestClose(Component owner) {
        return !runner.isRunning()
                || process.swing.SwingCancellationConfirmation.requestForVisibleRun(
                        owner, runner::cancel);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        runner.cancel();
    }

    public static JFrame openFrame(DomainModel domain, String title) {
        JFrame frame = new JFrame("Wikipedia Discovery — " + title);
        WikipediaDiscoveryPanel panel = new WikipediaDiscoveryPanel(domain);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.add(panel);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) {
                if (panel.requestClose(frame)) frame.dispose();
            }
            @Override public void windowClosed(java.awt.event.WindowEvent event) {
                panel.close();
            }
        });
        frame.setSize(1650, 950);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        return frame;
    }
}
