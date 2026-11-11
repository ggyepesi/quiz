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
import datasource.api.SourceBindingSlot;
import quiz.curation.FieldRulePromoter;
import quiz.curation.FieldSourceRecipe;
import quiz.transform.ui.ProjectBacking;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;

/**
 * Source workbench over a saved domain. It selects a bounded set and compares their
 * retained or freshly fetched Wikipedia source documents. When that domain carries its
 * editable ModelBuilder project, an explicitly selected infobox result may propose a
 * field-source configuration in the working model; Save remains the one persistence
 * boundary.
 */
public final class WikipediaDiscoveryPanel extends JPanel implements AutoCloseable {
    private static final int DEFAULT_SAMPLE = 12;

    private final TransformController domain;
    private final InitialSelection initialSelection;
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
    private final JButton configureInfobox =
            new JButton("Configure selected infobox value…");
    private final JButton saveModel = new JButton("Save model…");
    private final JLabel sourceStatus = new JLabel(" ");
    private final JLabel resultStatus = new JLabel(
            "Select instances, then analyze retained articles or fetch them from Wikipedia.");

    private final JPanel instancesHolder = new JPanel(new BorderLayout());
    private final JPanel selectedHolder = new JPanel(new BorderLayout());
    private final JPanel matchingHolder = new JPanel(new BorderLayout());
    private final JTabbedPane sourceTabs = new JTabbedPane();
    private final JTabbedPane resultTabs = new JTabbedPane();
    private final JLabel matchingStatus = new JLabel(
            "Select one structural pattern to show its matching original instances.");
    private final LinkedHashMap<String, WikidataDynamicObject> selected =
            new LinkedHashMap<>();
    private final ProjectBacking project;
    private final FieldRulePromoter promoter;
    private List<WikidataDynamicObject> classMembers = List.of();
    private List<WikidataDynamicObject> selectedInClass = List.of();
    private List<WikidataDynamicObject> selectedInSample = List.of();
    private SearchableView instancesView;
    private SearchableView selectedView;
    private SearchableView matchingView;
    private WikipediaDiscoveryRows.InfoboxPattern selectedInfobox;
    /** The Infobox values tab, the only one whose selection the configure action reads. */
    private SearchableView infoboxView;
    private boolean closed;

    public WikipediaDiscoveryPanel(DomainModel model) {
        this(model, null);
    }

    /** The same workbench may start empty (the standalone app) or with the exact
     *  instances explicitly selected in another domain-data view such as TransformApp. */
    public WikipediaDiscoveryPanel(DomainModel model, InitialSelection initialSelection) {
        super(new BorderLayout(6, 6));
        this.domain = new TransformController(model, new quiz.transform.app.DomainSaver());
        this.initialSelection = initialSelection;
        this.project = model.capability(ProjectBacking.class);
        this.promoter = model.capability(FieldRulePromoter.class);
        build();
        wire();
        populateTypes();
    }

    private void build() {
        saveModel.setText(project == null ? "Save project…"
                : "Save " + project.projectKind().toString().toLowerCase() + "…");
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
        controls.add(saveModel);

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
        JPanel resultHeader = new JPanel(new BorderLayout(4, 2));
        resultHeader.add(resultStatus, BorderLayout.CENTER);
        JPanel resultActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        resultActions.add(configureInfobox);
        resultHeader.add(resultActions, BorderLayout.EAST);
        results.add(resultHeader, BorderLayout.NORTH);

        JPanel matching = new JPanel(new BorderLayout(4, 3));
        matching.setBorder(BorderFactory.createTitledBorder("Matching instances"));
        matching.add(matchingStatus, BorderLayout.NORTH);
        matching.add(matchingHolder, BorderLayout.CENTER);
        clearMatchingGroup();

        JSplitPane resultSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                resultTabs, matching);
        resultSplit.setResizeWeight(0.63);
        resultSplit.setContinuousLayout(true);
        results.add(resultSplit, BorderLayout.CENTER);
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
        configureInfobox.addActionListener(event -> configureSelectedInfobox());
        saveModel.addActionListener(event -> saveModel());
        resultTabs.addChangeListener(event -> updateActions());
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
            String requested = initialSelection == null
                    ? null : initialSelection.className();
            boolean offered = requested != null
                    && java.util.stream.IntStream.range(0, type.getItemCount())
                    .mapToObj(type::getItemAt).anyMatch(requested::equals);
            if (offered) type.setSelectedItem(requested);
            else type.setSelectedIndex(0);
            showType((String) type.getSelectedItem());
            if (offered) seedInitialSelection();
        }
    }

    private void seedInitialSelection() {
        if (initialSelection == null) return;
        java.util.Map<String, WikidataDynamicObject> available = classMembers.stream()
                .collect(java.util.stream.Collectors.toMap(
                        WikipediaDiscoveryPanel::key, value -> value,
                        (first, ignored) -> first, java.util.LinkedHashMap::new));
        selected.clear();
        int omitted = 0;
        for (WikidataDynamicObject requested : initialSelection.instances()) {
            WikidataDynamicObject member = available.get(key(requested));
            if (member == null) omitted++;
            else putSelected(member);
        }
        selectedInSample = List.of();
        rebuildSelectedView();
        invalidateAnalysis("The selected articles came from "
                + initialSelection.origin() + ". Analyze them to produce results.");
        sourceTabs.setSelectedIndex(1);
        String limit = selected.size() <= WikipediaDiscoveryQuery.MAX_ARTICLES ? ""
                : " Fetch is disabled until you remove at least "
                        + (selected.size() - WikipediaDiscoveryQuery.MAX_ARTICLES)
                        + " selection(s); one run is bounded to "
                        + WikipediaDiscoveryQuery.MAX_ARTICLES + " articles.";
        sourceStatus.setText("Received exactly " + selected.size() + " selected "
                + initialSelection.className() + " instance(s) from "
                + initialSelection.origin() + ". No article has been fetched."
                + (omitted == 0 ? "" : " " + omitted
                        + " selected instance(s) had no usable English Wikipedia "
                        + "correspondence in this class and were not added.") + limit);
        updateActions();
    }

    private void showType(String className) {
        classMembers = className == null ? List.of() : wikipediaMembers(className);
        selected.clear();
        selectedInClass = List.of();
        selectedInSample = List.of();
        rebuildInstanceView();
        rebuildSelectedView();
        invalidateAnalysis("The class changed. Select instances and analyze its articles.");
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
                .selectionSetListener(chosen -> {
                    selection.accept(chosen.stream()
                            .filter(Viewable.class::isInstance)
                            .map(Viewable.class::cast).toList());
                    updateActions();
                })
                .collapsible(true)
                .build();
    }

    private void showStructuralGroup(List<Object> chosen) {
        WikipediaDiscoveryRows.StructuralGroup group = chosen != null && chosen.size() == 1
                && chosen.getFirst() instanceof WikipediaDiscoveryRows.StructuralGroup value
                ? value : null;
        if (group == null) {
            clearMatchingGroup();
            return;
        }
        String className = (String) type.getSelectedItem();
        matchingView = instanceView(group.members(), className, ignored -> { });
        matchingHolder.removeAll();
        matchingHolder.add(matchingView, BorderLayout.CENTER);
        matchingHolder.revalidate();
        matchingHolder.repaint();
        matchingStatus.setText(group.groupName() + " — " + group.members().size() + " "
                + className + " instance(s). Read-only discovery group; nothing is saved.");
    }

    private void clearMatchingGroup() {
        matchingView = null;
        matchingHolder.removeAll();
        matchingHolder.revalidate();
        matchingHolder.repaint();
        matchingStatus.setText(
                "Select one structural pattern to show its matching original instances.");
    }

    private void startRandomSample() {
        List<WikidataDynamicObject> shuffled = new ArrayList<>(classMembers);
        java.util.Collections.shuffle(shuffled);
        int count = Math.min((Integer) sampleSize.getValue(), shuffled.size());
        selected.clear();
        shuffled.stream().limit(count).forEach(this::putSelected);
        selectedInSample = List.of();
        rebuildSelectedView();
        invalidateAnalysis("The selected articles changed. Analyze them to refresh the results.");
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
        boolean firstAddition = selected.isEmpty() && !added.isEmpty();
        added.forEach(this::putSelected);
        if (selectedView == null || firstAddition) {
            // An empty SearchableView deliberately has no cards container. The first
            // addition creates that container; later additions stay local to it.
            rebuildSelectedView();
        } else {
            selectedView.appendViewables(added);
            updateSelectedTab();
        }
        if (!added.isEmpty()) {
            invalidateAnalysis("The selected articles changed. Analyze them to refresh the results.");
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
        if (!removed.isEmpty()) {
            invalidateAnalysis("The selected articles changed. Analyze them to refresh the results.");
        }
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
        selectedInfobox = null;
        clearMatchingGroup();
        resultTabs.removeAll();
        addResultTab("Articles", analysis.articles(), WikipediaSource.class,
                RenderingMode.CARD, "No structured articles are available.");
        addResultTab("Sections", analysis.sections(),
                WikipediaDiscoveryRows.SectionPattern.class, RenderingMode.CARD,
                "No section headings were retained.");
        addResultTab("Section contents", analysis.contents(),
                WikipediaDiscoveryRows.SectionContent.class, RenderingMode.CARD,
                "No section content blocks were retained.");
        addResultTab("List items", analysis.listItems(),
                WikipediaDiscoveryRows.ListItemContent.class, RenderingMode.CARD,
                "No structured list items were retained.");
        addResultTab("Table rows", analysis.tableRows(),
                WikipediaDiscoveryRows.TableRowContent.class, RenderingMode.CARD,
                "No structured table rows were retained.");
        addResultTab("Table headers", analysis.tableHeaders(),
                WikipediaDiscoveryRows.TableHeaderPattern.class, RenderingMode.CARD,
                "No authored table-header values were retained.");
        infoboxView = addResultTab("Infobox values", analysis.infoboxes(),
                WikipediaDiscoveryRows.InfoboxPattern.class, RenderingMode.CARD,
                "No infobox values were retained.", selected -> {
                    selectedInfobox = selected.size() == 1
                            && selected.getFirst()
                            instanceof WikipediaDiscoveryRows.InfoboxPattern value
                            ? value : null;
                    showStructuralGroup(selected);
                    updateActions();
                });
        addResultTab("Categories", analysis.categories(),
                WikipediaDiscoveryRows.CategoryPattern.class, RenderingMode.CARD,
                "No visible categories were retained.");
        addResultTab("Links by section", analysis.links(),
                WikipediaDiscoveryRows.LinkPattern.class, RenderingMode.CARD,
                "No article links were retained.");
        addResultTab("HTML elements", analysis.blockKinds(),
                WikipediaDiscoveryRows.BlockKindPattern.class, RenderingMode.CARD,
                analysis.blockKindsAvailable() ? "No structural HTML elements were found."
                        : "HTML elements are read from fetched HTML. A retained article keeps "
                                + "only the blocks its structure chose, so it cannot show "
                                + "what was dropped: press Fetch selected articles.");
        if (!analysis.unavailable().isEmpty()) {
            addResultTab("Problems", analysis.unavailable(),
                    WikipediaDiscoveryRows.UnavailableArticle.class, RenderingMode.CARD,
                    "Every selected article was available.");
        }
        resultStatus.setText("Compared " + analysis.availableArticles() + " of "
                + analysis.selectedArticles() + " selected " + source + " article(s): "
                + analysis.sections().size() + " section heading(s), "
                + analysis.contents().size() + " content block(s), "
                + analysis.listItems().size() + " list item(s), "
                + analysis.tableRows().size() + " table row(s), "
                + analysis.tableHeaders().size() + " table header pattern(s), "
                + analysis.infoboxes().size() + " infobox key(s), "
                + analysis.categories().size() + " category title(s), "
                + analysis.links().size() + " section/link pattern(s), and "
                + (analysis.blockKindsAvailable()
                        ? analysis.blockKinds().size() + " HTML element outcome(s)."
                        : "no HTML-element inventory (retained articles)."));
    }

    private <T extends Viewable> SearchableView addResultTab(String title, List<T> values,
            Class<T> rowType, RenderingMode mode, String emptyMessage) {
        return addResultTab(title, values, rowType, mode, emptyMessage,
                this::showStructuralGroup);
    }

    private <T extends Viewable> SearchableView addResultTab(String title, List<T> values,
            Class<T> rowType, RenderingMode mode, String emptyMessage,
            java.util.function.Consumer<List<Object>> selection) {
        SearchableView view = SearchableView.builder(values)
                .type(rowType)
                .mode(mode)
                .valueLinker(wikidata.ui.WikidataLinks.valueLinker())
                .emptyMessage(emptyMessage)
                .collapsible(true)
                .selectionSetListener(selection)
                .build();
        resultTabs.addTab(title + " — " + values.size(), view);
        return view;
    }

    private void emptyResults() {
        selectedInfobox = null;
        clearMatchingGroup();
        resultTabs.removeAll();
        addResultTab("Articles", List.of(), WikipediaSource.class, RenderingMode.CARD,
                "Select instances, then analyze retained articles or fetch them from "
                        + "Wikipedia.");
        addResultTab("Sections", List.of(), WikipediaDiscoveryRows.SectionPattern.class,
                RenderingMode.CARD, "Fetch selected articles to discover their sections.");
        addResultTab("Section contents", List.of(),
                WikipediaDiscoveryRows.SectionContent.class, RenderingMode.CARD,
                "Fetch selected articles to inspect the content retained under each section.");
        addResultTab("List items", List.of(),
                WikipediaDiscoveryRows.ListItemContent.class, RenderingMode.CARD,
                "Fetch selected articles to inspect nested list items.");
        addResultTab("Table rows", List.of(),
                WikipediaDiscoveryRows.TableRowContent.class, RenderingMode.CARD,
                "Fetch selected articles to inspect table captions, rows and cells.");
        addResultTab("Table headers", List.of(),
                WikipediaDiscoveryRows.TableHeaderPattern.class, RenderingMode.CARD,
                "Fetch selected articles to compare authored table-header values.");
        infoboxView = addResultTab("Infobox values", List.of(),
                WikipediaDiscoveryRows.InfoboxPattern.class, RenderingMode.CARD,
                "Fetch selected articles to discover their infobox rows, links and images.");
        addResultTab("Categories", List.of(), WikipediaDiscoveryRows.CategoryPattern.class,
                RenderingMode.CARD, "Fetch selected articles to discover their categories.");
        addResultTab("Links by section", List.of(),
                WikipediaDiscoveryRows.LinkPattern.class, RenderingMode.CARD,
                "Fetch selected articles to discover links by article section.");
        addResultTab("HTML elements", List.of(),
                WikipediaDiscoveryRows.BlockKindPattern.class, RenderingMode.CARD,
                "Fetch selected articles to inspect how their structural HTML elements "
                        + "were retained.");
    }

    private void invalidateAnalysis(String message) {
        emptyResults();
        resultStatus.setText(message);
    }

    private void configureSelectedInfobox() {
        WikipediaDiscoveryRows.InfoboxPattern observed = selectedInfobox;
        String className = (String) type.getSelectedItem();
        if (observed == null || className == null || project == null || promoter == null
                || project.projectModel() == null) return;
        String conflict = project.modelWriteConflict();
        if (!conflict.isEmpty()) {
            JOptionPane.showMessageDialog(this, conflict,
                    "Cannot edit configuration", JOptionPane.ERROR_MESSAGE);
            return;
        }
        GeneratedClassModel owner = project.projectModel().findClass(className);
        List<FieldChoice> fields = owner == null ? List.of()
                : eligibleFieldChoices(project.projectModel(), className, observed, promoter);
        if (fields.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "The model exposes no field that can take this infobox value for "
                            + className + ".\n\nIts text can fill one acquired, single-valued "
                            + "String or Text field. A rendered row's links can instead be "
                            + "candidate evidence for an entity list with a declared target "
                            + "class. Add or change the target field in ModelBuilder first; "
                            + "discovery will not infer its type or cardinality from a sample.",
                    "No target field", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JComboBox<FieldChoice> field = new JComboBox<>(fields.toArray(FieldChoice[]::new));
        JComboBox<SourcePlace> place = new JComboBox<>();
        JTextArea preview = new JTextArea();
        preview.setEditable(false);
        preview.setLineWrap(true);
        preview.setWrapStyleWord(true);
        preview.setRows(12);
        preview.setColumns(64);
        Runnable refresh = () -> {
            FieldChoice target = (FieldChoice) field.getSelectedItem();
            SourcePlace selectedPlace = (SourcePlace) place.getSelectedItem();
            if (target == null || selectedPlace == null) return;
            FieldRulePromoter.PromotionPreview promotion = promoter.previewPromotion(
                    infoboxRecipe(observed, target, selectedPlace));
            preview.setText(configurationDescription(
                    observed, target, selectedPlace, promotion, project.modelFile()));
            preview.setCaretPosition(0);
        };
        Runnable refreshPlaces = () -> {
            FieldChoice target = (FieldChoice) field.getSelectedItem();
            if (target == null) return;
            List<SourcePlace> eligible = eligiblePlaces(observed, target, promoter);
            place.setModel(new DefaultComboBoxModel<>(eligible.toArray(SourcePlace[]::new)));
            boolean hasPrimary = wikidata.explore.model.FieldSourceBindings.binding(
                    target.field(), SourceBindingSlot.PRIMARY_FIELD_VALUE) != null;
            SourcePlace preferred = hasPrimary && eligible.contains(SourcePlace.FALLBACK)
                    ? SourcePlace.FALLBACK : eligible.getFirst();
            place.setSelectedItem(preferred);
            refresh.run();
        };
        field.addActionListener(event -> refreshPlaces.run());
        place.addActionListener(event -> refresh.run());
        refreshPlaces.run();

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(3, 3, 3, 8);
        form.add(new JLabel("Target field:"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(field, c);
        c.gridx = 0;
        c.gridy++;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        form.add(new JLabel("Use as:"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(place, c);
        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(10, 3, 3, 3);
        form.add(new JScrollPane(preview), c);

        Object[] options = {"Apply configuration", "Cancel"};
        int answer = JOptionPane.showOptionDialog(quiz.ui.Dialogs.owner(this), form,
                "Configure from Wikipedia discovery", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
        if (answer != 0) return;
        FieldChoice target = (FieldChoice) field.getSelectedItem();
        SourcePlace selectedPlace = (SourcePlace) place.getSelectedItem();
        if (target == null || selectedPlace == null) return;
        FieldSourceRecipe recipe = infoboxRecipe(observed, target, selectedPlace);
        FieldRulePromoter.PromotionPreview promotion = promoter.previewPromotion(recipe);
        if (!promotion.eligible()) {
            JOptionPane.showMessageDialog(this, promotion.reason(),
                    "Configuration not applied", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            promoter.promote(recipe);
            resultStatus.setText("Configured " + target.owner() + "."
                    + target.field().name() + " from " + observed.key() + " as its "
                    + selectedPlace.label().toLowerCase() + ". Nothing has been saved yet; "
                    + saveModel.getText().replace("…", "") + " writes "
                    + project.modelFile().getPath() + ".");
            updateActions();
        } catch (Exception failure) {
            JOptionPane.showMessageDialog(this,
                    "Configuration failed: " + failure.getMessage(),
                    "Configuration failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    static String configurationDescription(
            WikipediaDiscoveryRows.InfoboxPattern observed,
            FieldChoice target, SourcePlace place,
            FieldRulePromoter.PromotionPreview promotion, java.io.File modelFile) {
        String existing = promotion.previousProperty().isBlank()
                ? "No source is currently configured in this slot."
                : "This replaces " + promotion.previousProperty() + ".";
        return "Configure " + target.owner() + "." + target.field().name() + " ("
                + target.field().displayType() + ")\n\n"
                + "Source: " + (place == SourcePlace.CANDIDATE_EVIDENCE
                        ? "links retained in Wikipedia infobox row "
                        : "Wikipedia infobox value ") + observed.key() + "\n"
                + "Use as: " + place.label() + "\n"
                + "Action: " + promotion.reason() + "\n"
                + "Observed coverage: " + observed.articles() + " of "
                + observed.selectedArticles() + " selected articles\n"
                + existing + "\n\n"
                + "Apply changes only the working configuration. It does not fetch values "
                + "and does not write a file. Save writes the model to\n"
                + (modelFile == null ? "(no model file)" : modelFile.getPath()) + ".";
    }

    static List<FieldChoice> declaredFieldChoices(
            wikidata.explore.model.GeneratedProjectModel model, String className) {
        if (model == null || className == null) return List.of();
        java.util.LinkedHashMap<String, FieldChoice> choices = new java.util.LinkedHashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (GeneratedClassModel current = model.findClass(className);
             current != null && seen.add(current.className());
             current = current.baseClassName().isBlank()
                     ? null : model.findClass(current.baseClassName())) {
            if (current.isImported()) continue;
            for (GeneratedFieldModel field : current.fields()) {
                if (field != null) {
                    choices.putIfAbsent(field.name(),
                            new FieldChoice(current.className(), field));
                }
            }
        }
        return List.copyOf(choices.values());
    }

    static List<FieldChoice> eligibleFieldChoices(
            wikidata.explore.model.GeneratedProjectModel model, String className,
            WikipediaDiscoveryRows.InfoboxPattern observed, FieldRulePromoter promoter) {
        if (promoter == null || observed == null) return List.of();
        return declaredFieldChoices(model, className).stream()
                .filter(field -> !eligiblePlaces(observed, field, promoter).isEmpty())
                .toList();
    }

    private static List<SourcePlace> eligiblePlaces(
            WikipediaDiscoveryRows.InfoboxPattern observed, FieldChoice target,
            FieldRulePromoter promoter) {
        return java.util.Arrays.stream(SourcePlace.values())
                .filter(place -> promoter.previewPromotion(
                        infoboxRecipe(observed, target, place)).eligible())
                .toList();
    }

    private static FieldSourceRecipe infoboxRecipe(
            WikipediaDiscoveryRows.InfoboxPattern observed,
            FieldChoice target, SourcePlace place) {
        if (place == SourcePlace.CANDIDATE_EVIDENCE) {
            return FieldSourceRecipe.wikipediaInfoboxLinks(
                    target.owner(), target.field().name(), observed.key(),
                    "Rendered infobox row links");
        }
        return FieldSourceRecipe.wikipediaInfobox(
                target.owner(), target.field().name(), place.slot(), observed.key(),
                wikidata.explore.model.FieldSourceType.WIKIPEDIA_INFOBOX.toString());
    }

    /** TransformApp's Save, the same operation: it writes the project the domain was
     *  opened from, under that project's own name. A guessed name is not that project,
     *  and DomainSaver would write it as a detached dataset without the model. */
    private void saveModel() {
        if (project == null || project.unsavedModelChanges().isEmpty()) return;
        String name = project.projectName();
        String kind = project.projectKind().toString().toLowerCase();
        if (name == null || name.isBlank()) {
            JOptionPane.showMessageDialog(this,
                    "This " + kind + " has no project name, so there is no saved project "
                            + "to write. Nothing was saved.",
                    "Save " + kind, JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!quiz.ui.Dialogs.confirmPersistence(
                this, "Save " + kind, domain.describeSaveAsDomain(name))) return;
        try {
            quiz.ui.Dialogs.showPersistenceSuccess(
                    this, "Save " + kind, name, domain.saveAsDomain(name));
            updateActions();
        } catch (Exception failure) {
            JOptionPane.showMessageDialog(this, "Save failed: " + failure.getMessage(),
                    "Save failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    record FieldChoice(String owner, GeneratedFieldModel field) {
        @Override public String toString() {
            return owner + "." + field.name() + " — " + field.displayType();
        }
    }

    enum SourcePlace {
        PRIMARY("Primary source", SourceBindingSlot.PRIMARY_FIELD_VALUE),
        FALLBACK("Fallback source", SourceBindingSlot.FALLBACK_FIELD_VALUE),
        CANDIDATE_EVIDENCE(
                "Candidate evidence", SourceBindingSlot.CANDIDATE_EVIDENCE);

        private final String label;
        private final SourceBindingSlot slot;
        SourcePlace(String label, SourceBindingSlot slot) {
            this.label = label;
            this.slot = slot;
        }
        String label() { return label; }
        SourceBindingSlot slot() { return slot; }
        @Override public String toString() { return label; }
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
        boolean infoboxTab = resultTabs.getSelectedComponent() != null
                && resultTabs.getSelectedComponent() == infoboxView;
        configureInfobox.setEnabled(!running && infoboxTab && selectedInfobox != null
                && project != null && promoter != null && project.projectModel() != null);
        configureInfobox.setToolTipText(project == null || promoter == null
                ? "This saved domain has no editable ModelBuilder project."
                : selectedInfobox == null
                        ? "Select exactly one value on the Infobox values tab."
                        : "Configure the selected observed value as a source for an existing field.");
        saveModel.setEnabled(!running && project != null
                && !project.unsavedModelChanges().isEmpty());
        saveModel.setToolTipText(project == null
                ? "This saved domain has no editable ModelBuilder project."
                : project.unsavedModelChanges().isEmpty()
                        ? "There are no unsaved configuration changes."
                        : "Save " + project.unsavedModelChanges().size()
                                + " configuration change(s) to "
                                + project.modelFile().getPath() + ".");
    }

    private static List<WikidataDynamicObject> dynamic(List<Viewable> values) {
        return values.stream().filter(WikidataDynamicObject.class::isInstance)
                .map(WikidataDynamicObject.class::cast).toList();
    }

    public boolean requestClose(Component owner) {
        if (!requestStopRun(owner)) return false;
        if (project == null || project.unsavedModelChanges().isEmpty()) return true;
        Object[] options = {"Keep open", "Discard and close"};
        int choice = JOptionPane.showOptionDialog(this,
                "These configuration changes have not been saved to\n"
                        + project.modelFile().getPath() + ":\n\n  - "
                        + String.join("\n  - ", project.unsavedModelChanges())
                        + "\n\nUse \"" + saveModel.getText().replace("…", "")
                        + "\" to keep them.",
                "Unsaved configuration", JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        return choice == 1;
    }

    /** Whether running work may stop: keeping it is the default. A caller that owns the
     *  working model (TransformApp) asks only this, because model edits made here stay
     *  pending in that caller and are guarded by its own close. */
    public boolean requestStopRun(Component owner) {
        return !runner.isRunning()
                || process.swing.SwingCancellationConfirmation.requestForVisibleRun(
                        owner, runner::cancel);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        runner.cancel();
    }

    /** Explicit handoff into the shared workbench. The source view supplies the logical
     * class and instances; discovery never reconstructs that selection from names. */
    public record InitialSelection(
            String className, List<WikidataDynamicObject> instances, String origin) {
        public InitialSelection {
            className = className == null ? "" : className.trim();
            instances = instances == null ? List.of()
                    : instances.stream().filter(java.util.Objects::nonNull)
                    .distinct().toList();
            origin = origin == null || origin.isBlank() ? "the originating view"
                    : origin.trim();
        }
    }

    public static JFrame openFrame(DomainModel domain, String title) {
        return openFrame(domain, title, null);
    }

    public static JFrame openFrame(
            DomainModel domain, String title, InitialSelection initialSelection) {
        return openWindow(domain, title, initialSelection, null).window();
    }

    /** One window lifecycle for both the standalone navigator and contextual callers.
     *  {@code owner} is the workbench that owns the working model, or null when this
     *  window owns it. An owned window's model edits stay pending in its owner, which
     *  guards them, so closing it asks only about a running fetch: offering to "discard"
     *  them here would name a loss that does not happen. */
    public static OpenedWindow openWindow(
            DomainModel domain, String title, InitialSelection initialSelection,
            Component owner) {
        JFrame frame = new JFrame("Wikipedia Discovery — " + title);
        WikipediaDiscoveryPanel panel = new WikipediaDiscoveryPanel(domain, initialSelection);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.add(panel);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) {
                boolean close = owner == null ? panel.requestClose(frame)
                        : panel.requestStopRun(frame);
                if (close) frame.dispose();
            }
            @Override public void windowClosed(java.awt.event.WindowEvent event) {
                panel.close();
            }
        });
        frame.setSize(1650, 950);
        frame.setLocationRelativeTo(owner);
        frame.setVisible(true);
        return new OpenedWindow(frame, panel);
    }

    public record OpenedWindow(JFrame window, WikipediaDiscoveryPanel workbench) { }
}
