package wikipedia.discovery;

import domain.DomainModel;
import objectview.Viewable;
import objectview.group.DefaultViewableGroup;
import objectview.group.ViewableGroup;
import objectview.render.GroupMembersView;
import objectview.render.RenderingMode;
import objectview.view.SearchableView;
import quiz.source.WikipediaSource;
import quiz.transform.ui.BoundedInstanceSelection;
import quiz.transform.ui.TransformController;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.query.swing.SwingQueryRunner;
import wikidata.explore.query.swing.SwingQuerySession;
import work.QueryContext;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import datasource.api.SourceBindingSlot;
import quiz.curation.FieldRulePromoter;
import quiz.curation.FieldSourceRecipe;
import quiz.transform.ui.ProjectBacking;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;

/**
 * The Wikipedia tab of the Discovery workbench. It compares the retained or freshly
 * fetched Wikipedia source documents of the workbench's selected instances that have an
 * English Wikipedia article correspondence. When that domain carries its
 * editable ModelBuilder project, an explicitly selected infobox result may propose a
 * field-source configuration in the working model; Save remains the one persistence
 * boundary.
 */
public final class WikipediaDiscoveryPanel extends JPanel implements AutoCloseable {
    private final TransformController domain;
    private final SwingQuerySession queries = new SwingQuerySession(new QueryContext());
    private final SwingQueryRunner runner = queries.runner();

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

    private final JTabbedPane resultTabs = new JTabbedPane();
    private final ProjectBacking project;
    private final FieldRulePromoter promoter;
    private final BoundedInstanceSelection selection;
    private GroupMembersView structuralGroupsView;
    private final IdentityHashMap<WikipediaDiscoveryRows.StructuralGroup, DiscoveryGroup>
            structuralGroups = new IdentityHashMap<>();
    private WikipediaDiscoveryRows.InfoboxPattern selectedInfobox;
    /** The Infobox values tab, the only one whose selection the configure action reads. */
    private SearchableView infoboxView;
    private boolean closed;

    public WikipediaDiscoveryPanel(TransformController domain,
            BoundedInstanceSelection selection) {
        super(new BorderLayout(6, 6));
        this.domain = java.util.Objects.requireNonNull(domain);
        this.project = domain.domain().capability(ProjectBacking.class);
        this.promoter = domain.domain().capability(FieldRulePromoter.class);
        this.selection = java.util.Objects.requireNonNull(selection);
        selection.addHost(new BoundedInstanceSelection.Host() {
            @Override public void selectionChanged(String reason) {
                invalidateAnalysis(reason);
            }
            @Override public void updateActions() {
                WikipediaDiscoveryPanel.this.updateActions();
            }
        });
        build();
        wire();
        updateActions();
    }

    /** The selected instances this source can read: those with an English Wikipedia
     *  article correspondence. The others are counted, never silently dropped. */
    private List<WikidataDynamicObject> articles() {
        return selection.selected().stream()
                .filter(value -> !value.enwikiTitle().isBlank()).toList();
    }

    private String withoutArticles() {
        int missing = selection.size() - articles().size();
        return missing == 0 ? "" : " " + missing + " selected instance(s) have no English "
                + "Wikipedia article correspondence and are not read.";
    }

    private void build() {
        saveModel.setText(project == null ? "Save project…"
                : "Save " + project.projectKind().toString().toLowerCase() + "…");
        JPanel controls = new JPanel(new objectview.utils.swing.WrapLayout(
                FlowLayout.LEFT, 6, 2));
        controls.add(retained);
        controls.add(fetch);
        controls.add(cancel);
        controls.add(logs);
        controls.add(saveModel);

        JPanel north = new JPanel(new BorderLayout(4, 3));
        north.add(controls, BorderLayout.NORTH);
        north.add(sourceStatus, BorderLayout.SOUTH);

        JPanel results = new JPanel(new BorderLayout(4, 4));
        JPanel resultHeader = new JPanel(new BorderLayout(4, 2));
        resultHeader.add(resultStatus, BorderLayout.CENTER);
        JPanel resultActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        resultActions.add(configureInfobox);
        resultHeader.add(resultActions, BorderLayout.EAST);
        results.add(resultHeader, BorderLayout.NORTH);
        results.add(resultTabs, BorderLayout.CENTER);
        emptyResults();

        north.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        results.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
        add(north, BorderLayout.NORTH);
        add(results, BorderLayout.CENTER);
    }

    private void wire() {
        retained.addActionListener(event -> analyzeRetained());
        fetch.addActionListener(event -> fetchSelected());
        logs.addActionListener(event -> queries.showLogs(this));
        configureInfobox.addActionListener(event -> configureSelectedInfobox());
        saveModel.addActionListener(event -> saveModel());
        resultTabs.addChangeListener(event -> updateActions());
        runner.registerRunButton(fetch);
        runner.registerCancelButton(cancel);
        runner.onRunningChanged(running -> {
            selection.setRunning(this, running);
            updateActions();
            if (running) {
                sourceStatus.setText("Fetching exactly " + articles().size()
                        + " selected structured article(s), each with its infobox in the "
                        + "same request.");
            }
        });
    }

    private void showStructuralGroup(List<Object> chosen) {
        WikipediaDiscoveryRows.StructuralGroup group = chosen != null && chosen.size() == 1
                && chosen.getFirst() instanceof WikipediaDiscoveryRows.StructuralGroup value
                ? value : null;
        DiscoveryGroup selectedGroup = group == null ? null : structuralGroups.get(group);
        if (selectedGroup == null || structuralGroupsView == null) return;
        structuralGroupsView.selectGroup(selectedGroup, true);
        resultStatus.setText(group.groupName() + " — " + group.members().size()
                + " matching instance(s). Open Structural groups to inspect them; "
                + "this read-only group is not saved.");
    }

    private void addStructuralGroups(WikipediaStructureAnalysis.Result analysis) {
        structuralGroups.clear();
        DiscoveryGroup root = new DiscoveryGroup("All selected instances");
        articles().forEach(value -> root.addMember(value, false));
        int count = 0;
        if (analysis != null) {
            count += addSectionFacet(root, analysis.sections(), analysis.contents(),
                    analysis.listItems(), analysis.tableRows());
            count += addStructuralFacet(root, "Infobox keys", analysis.infoboxes());
            count += addStructuralFacet(root, "Categories", analysis.categories());
            count += addStructuralFacet(root, "Links by section", analysis.links());
            count += addStructuralFacet(root, "Table headers", analysis.tableHeaders());
            count += addStructuralFacet(root, "HTML elements", analysis.blockKinds());
        }
        String className = selection.className();
        structuralGroupsView = new GroupMembersView(root, group -> {
            JPanel members = new JPanel(new BorderLayout(4, 4));
            members.setBorder(BorderFactory.createTitledBorder(
                    group.getDisplayName() + " — " + group.getMembers().size()
                            + " matching instance(s)"));
            members.add(selection.instanceView(new ArrayList<>(group.getMembers()), className,
                    ignored -> { }), BorderLayout.CENTER);
            return members;
        }, JSplitPane.HORIZONTAL_SPLIT, false, 0.28, true);
        structuralGroupsView.groups().setStatusText(count == 0
                ? "Fetch or analyze articles to discover structural groups"
                : "Select a read-only structural group; nothing here is saved");
        structuralGroupsView.selectGroup(root, true);
        resultTabs.addTab("Structural groups — " + count, structuralGroupsView);
    }

    private int addStructuralFacet(DiscoveryGroup root, String name,
            List<? extends WikipediaDiscoveryRows.StructuralGroup> patterns) {
        if (patterns == null || patterns.isEmpty()) return 0;
        DiscoveryGroup facet = root.getOrCreateChild(name).role(ViewableGroup.Role.FACET);
        for (WikipediaDiscoveryRows.StructuralGroup pattern : patterns) {
            DiscoveryGroup bucket = facet.getOrCreateChild(pattern.groupName())
                    .role(ViewableGroup.Role.BUCKET);
            DiscoveryGroup result = bucket;
            pattern.members().forEach(member -> result.addMember(member, true));
            structuralGroups.put(pattern, bucket);
        }
        return patterns.size();
    }

    private int addSectionFacet(DiscoveryGroup root,
            List<WikipediaDiscoveryRows.SectionPattern> patterns,
            List<WikipediaDiscoveryRows.SectionContent> contents,
            List<WikipediaDiscoveryRows.ListItemContent> listItems,
            List<WikipediaDiscoveryRows.TableRowContent> tableRows) {
        if ((patterns == null || patterns.isEmpty())
                && (contents == null || contents.isEmpty())
                && (listItems == null || listItems.isEmpty())
                && (tableRows == null || tableRows.isEmpty())) return 0;
        DiscoveryGroup facet = root.getOrCreateChild("Sections")
                .role(ViewableGroup.Role.FACET);
        for (WikipediaDiscoveryRows.SectionPattern pattern :
                patterns == null ? List.<WikipediaDiscoveryRows.SectionPattern>of()
                        : patterns) {
            DiscoveryGroup bucket = sectionGroup(facet, pattern.getDisplayName());
            DiscoveryGroup result = bucket;
            pattern.members().forEach(member -> result.addMember(member, true));
            structuralGroups.put(pattern, bucket);
        }
        addSectionContents(facet, contents == null ? List.of() : contents,
                listItems == null ? List.of() : listItems,
                tableRows == null ? List.of() : tableRows);
        return (patterns == null ? 0 : patterns.size())
                + (contents == null ? 0 : contents.size())
                + (listItems == null ? 0 : listItems.size())
                + (tableRows == null ? 0 : tableRows.size());
    }

    private void addSectionContents(DiscoveryGroup sections,
            List<WikipediaDiscoveryRows.SectionContent> contents,
            List<WikipediaDiscoveryRows.ListItemContent> items,
            List<WikipediaDiscoveryRows.TableRowContent> rows) {
        LinkedHashMap<String, Integer> listSizes = new LinkedHashMap<>();
        for (WikipediaDiscoveryRows.ListItemContent item : items) {
            listSizes.merge(listIdentity(item), 1, Integer::sum);
        }
        LinkedHashMap<String, Integer> tableSizes = new LinkedHashMap<>();
        for (WikipediaDiscoveryRows.TableRowContent row : rows) {
            tableSizes.merge(tableIdentity(row), 1, Integer::sum);
        }
        LinkedHashMap<String, DiscoveryGroup> lists = new LinkedHashMap<>();
        LinkedHashMap<String, DiscoveryGroup> tables = new LinkedHashMap<>();
        for (WikipediaDiscoveryRows.SectionContent content : contents) {
            DiscoveryGroup section = sectionGroup(sections, content.sectionPath());
            DiscoveryGroup node;
            if (content.list() != null) {
                String identity = content.articleTitle() + '\0' + content.sectionPath()
                        + '\0' + content.list().getDisplayName();
                int size = listSizes.getOrDefault(identity,
                        content.list().items().size());
                node = section.getOrCreateChild(containerLabel(
                        content.list().getDisplayName(), size, "item",
                        content.articleTitle()))
                        .role(ViewableGroup.Role.BUCKET);
                lists.put(identity, node);
            } else if (content.table() != null) {
                String identity = content.articleTitle() + '\0' + content.sectionPath()
                        + '\0' + content.table().getDisplayName();
                int size = tableSizes.getOrDefault(identity,
                        content.table().rows().size());
                node = section.getOrCreateChild(containerLabel(
                        content.table().getDisplayName(), size, "row",
                        content.articleTitle()))
                        .role(ViewableGroup.Role.BUCKET);
                tables.put(identity, node);
            } else {
                node = section.getOrCreateChild(content.kind() + ": "
                        + content.getDisplayName()).role(ViewableGroup.Role.BUCKET);
            }
            addMembers(node, content.members());
            structuralGroups.put(content, node);
        }
        for (WikipediaDiscoveryRows.ListItemContent item : items) {
            DiscoveryGroup section = sectionGroup(sections, item.sectionPath());
            // A parent item precedes its nested items, so its node already exists.
            DiscoveryGroup parentItem = item.parent() == null ? null
                    : structuralGroups.get(item.parent());
            DiscoveryGroup owner = parentItem == null ? section : parentItem;
            int size = listSizes.getOrDefault(listIdentity(item), 0);
            DiscoveryGroup list = lists.computeIfAbsent(listIdentity(item), ignored ->
                    owner.getOrCreateChild(containerLabel(item.list(), size, "item",
                                    item.articleTitle()))
                            .role(ViewableGroup.Role.BUCKET));
            DiscoveryGroup bucket = list.getOrCreateChild(item.getDisplayName())
                    .role(ViewableGroup.Role.BUCKET);
            addMembers(bucket, item.members());
            structuralGroups.put(item, bucket);
        }
        for (WikipediaDiscoveryRows.TableRowContent row : rows) {
            DiscoveryGroup section = sectionGroup(sections, row.sectionPath());
            int size = tableSizes.getOrDefault(tableIdentity(row), 0);
            DiscoveryGroup table = tables.computeIfAbsent(tableIdentity(row), ignored ->
                    section.getOrCreateChild(containerLabel(row.table(), size, "row",
                                    row.articleTitle()))
                            .role(ViewableGroup.Role.BUCKET));
            DiscoveryGroup bucket = table.getOrCreateChild(row.getDisplayName())
                    .role(ViewableGroup.Role.BUCKET);
            addMembers(bucket, row.members());
            structuralGroups.put(row, bucket);
        }
    }

    private static void addMembers(DiscoveryGroup group,
            List<WikidataDynamicObject> members) {
        members.forEach(member -> group.addMember(member, true));
    }

    /** A list or table belongs to one article, so its node names the article; only the
     *  section path above it is shared across the selected articles. */
    private static String containerLabel(
            String name, int size, String itemName, String article) {
        return name + " — " + size + ' ' + itemName + (size == 1 ? "" : "s")
                + " (" + article + ')';
    }

    private static DiscoveryGroup sectionGroup(DiscoveryGroup sections, String path) {
        DiscoveryGroup result = sections;
        for (String part : path.split("\\s*›\\s*")) {
            if (!part.isBlank()) {
                result = result.getOrCreateChild(part.strip())
                        .role(ViewableGroup.Role.BUCKET);
            }
        }
        return result;
    }

    private static String listIdentity(WikipediaDiscoveryRows.ListItemContent item) {
        return item.articleTitle() + '\0' + item.sectionPath() + '\0' + item.list();
    }

    private static String tableIdentity(WikipediaDiscoveryRows.TableRowContent row) {
        return row.articleTitle() + '\0' + row.sectionPath() + '\0' + row.table();
    }


    private void analyzeRetained() {
        List<WikipediaDiscoveryQuery.Article> values = WikipediaDiscoveryQuery.retained(
                articles());
        showAnalysis(WikipediaStructureAnalysis.analyze(values), "retained");
    }

    private void fetchSelected() {
        List<WikidataDynamicObject> values = articles();
        if (values.isEmpty()) return;
        sourceStatus.setText("Fetching exactly " + values.size()
                + " selected structured article(s), each with its infobox in the "
                + "same request." + withoutArticles());
        runner.run(new WikipediaDiscoveryQuery(values), result -> SwingUtilities.invokeLater(() -> {
            showAnalysis(WikipediaStructureAnalysis.analyze(result), "fetched");
            sourceStatus.setText("Fetched and compared " + result.stream()
                    .filter(WikipediaDiscoveryQuery.Article::available).count() + " of "
                    + result.size() + " selected article(s). Query logs contain every request."
                    + withoutArticles());
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
        resultTabs.removeAll();
        addStructuralGroups(analysis);
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
        resultTabs.removeAll();
        addStructuralGroups(null);
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
        String className = selection.className();
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
        int selectedCount = selection == null ? 0 : articles().size();
        boolean hasSelection = selectedCount > 0;
        boolean bounded = selectedCount <= WikipediaDiscoveryQuery.MAX_ARTICLES;
        retained.setEnabled(!running && hasSelection);
        fetch.setEnabled(!running && hasSelection && bounded);
        fetch.setText(running ? "Fetching selected articles…"
                : "Fetch " + selectedCount + " selected article"
                        + (selectedCount == 1 ? "" : "s"));
        fetch.setToolTipText(!hasSelection
                ? "Select at least one instance with an English Wikipedia article."
                : bounded ? null : "One run is bounded to "
                        + WikipediaDiscoveryQuery.MAX_ARTICLES + " articles; remove "
                        + (selectedCount - WikipediaDiscoveryQuery.MAX_ARTICLES)
                        + " from the selection.");
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

    /** UI adapter only: the discovered pattern remains the source of membership. */
    private static final class DiscoveryGroup
            extends DefaultViewableGroup<WikidataDynamicObject, DiscoveryGroup> {
        private DiscoveryGroup(String name) { super(name); }
        @Override protected DiscoveryGroup newChild(String name) {
            return new DiscoveryGroup(name);
        }
    }
}
