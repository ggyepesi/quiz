package wikidata.explore.workbench;

import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldSampleContext;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.MembershipPattern;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.model.StatementClassSource;
import wikidata.explore.query.swing.SwingQueryRunner;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.rule.RuleTreeCompiler;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import workbench.ExploreByExamplePanel;
import workbench.SelectionsButton;
import workbench.WorkbenchSelections;

/**
 * Hosts the mutually exclusive editors for qid-identity source classes,
 * statement-reification classes and fields.
 */
public class ModelSourceWorkbenchPanel extends JPanel implements AutoCloseable {

    private final GeneratedProjectModel projectModel;
    private final WorkbenchSelections selections = new WorkbenchSelections();

    private final ClassSourcePanel classSourcePanel =
            new ClassSourcePanel();
    private final FieldSourcePanel fieldSourcePanel =
            new FieldSourcePanel();
    private final StatementSourcePanel statementSourcePanel =
            new StatementSourcePanel();
    private final OwnedClassPanel ownedClassPanel;
    private final AggregateClassPanel aggregateClassPanel;

    private final JComboBox<String> kindBox =
            new JComboBox<>(
                    new String[]{
                            "Source class",
                            "Statement class",
                            "Owned class",
                            "Aggregate class"
                    });

    /** Says, above the editor, that what is shown belongs to another model. Visible
     *  only for an imported class, whose controls are all disabled — without it the
     *  editor would look merely broken. */
    private final JLabel importedNotice = new JLabel();
    private final JPanel kindHeader =
            new JPanel(
                    new FlowLayout(
                            FlowLayout.LEFT,
                            6,
                            2));
    private final JPanel reusableSelectionsPanel =
            new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));

    private boolean updatingKind = false;
    private boolean editingEnabled = true;

    private final JPanel cardPanel =
            new JPanel(new CardLayout());
    private final DomainOverviewPanel domainOverview;
    private SelectionViewerPanel selectionEditor;

    private final JTabbedPane helperTabs =
            new JTabbedPane(
                    JTabbedPane.TOP,
                    JTabbedPane.SCROLL_TAB_LAYOUT);
    private final JTabbedPane wikidataTools =
            new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
    private final JTabbedPane wikipediaTools =
            new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);

    private final NodeSamplePanel samplePanel =
            new NodeSamplePanel();
    private final PropertyDiscoveryPanel discoveryPanel =
            new PropertyDiscoveryPanel();
    private final WikiProjectSeedPanel wikiProjectPanel =
            new WikiProjectSeedPanel();
    private final ExploreByExamplePanel explorePanel =
            new ExploreByExamplePanel();
    private final CategorySeedPanel categoryPanel =
            new CategorySeedPanel();
    private final CachedPropertyViewablePanel propertyPanel =
            new CachedPropertyViewablePanel();
    private final EntityRelationDiscoveryPanel entityRelationPanel =
            new EntityRelationDiscoveryPanel();
    private final GraphPatternSamplePanel graphPatternPanel;
    private final GraphConfigurationDiagram graphConfigurationDiagram;

    private Object selected;
    /** Applying the editor being left invokes afterChange, whose model-tree refresh can
     * fire the selection listener again before the outer transition has completed. */
    private boolean changingSelection;

    private Consumer<Void> afterChange =
            ignored -> {};
    private Consumer<GeneratedFieldModel> afterApplyField =
            field -> {};
    private Consumer<GeneratedFieldModel> onFieldAddedFromTool =
            field -> {};
    private Runnable onShowHelperTools =
            () -> {};

    public ModelSourceWorkbenchPanel(
            GeneratedProjectModel projectModel) {

        super(new BorderLayout(4, 4));
        this.projectModel = projectModel;
        this.domainOverview = new DomainOverviewPanel(projectModel);
        this.graphPatternPanel = new GraphPatternSamplePanel(projectModel);
        this.graphConfigurationDiagram = new GraphConfigurationDiagram(projectModel);
        this.ownedClassPanel = new OwnedClassPanel(projectModel);
        this.aggregateClassPanel = new AggregateClassPanel(projectModel);
        this.explorePanel.selections(selections);
        this.propertyPanel.selections(selections);
        this.entityRelationPanel.selections(selections);
        this.discoveryPanel.selections(selections);
        this.ownedClassPanel.afterChange(ignored -> afterChange.accept(null));
        this.aggregateClassPanel.afterChange(ignored -> afterChange.accept(null));

        classSourcePanel.baseClassCandidates(
                () -> projectModel.classes()
                                  .stream()
                                  .map(
                                          GeneratedClassModel::className)
                                  .toList());
        classSourcePanel.setProjectModel(projectModel);

        statementSourcePanel.sourceClassCandidates(
                () -> projectModel.classes()
                                  .stream()
                                  .map(
                                          GeneratedClassModel::className)
                                  .toList());

        statementSourcePanel.setProjectModel(
                projectModel);
        buildUi();
    }

    public void setQueryRunner(
            SwingQueryRunner queryRunner) {

        classSourcePanel.setQueryRunner(queryRunner);
        fieldSourcePanel.setQueryRunner(queryRunner);
        samplePanel.setQueryRunner(queryRunner);
        discoveryPanel.setQueryRunner(queryRunner);
        wikiProjectPanel.setQueryRunner(queryRunner);
        explorePanel.setQueryRunner(queryRunner);
        categoryPanel.setQueryRunner(queryRunner);
        graphPatternPanel.setQueryRunner(queryRunner);
        entityRelationPanel.setQueryRunner(queryRunner);
    }

    public void afterChange(
            Consumer<Void> afterChange) {

        Consumer<Void> downstream = afterChange == null ? ignored -> {} : afterChange;
        this.afterChange = ignored -> {
            graphConfigurationDiagram.refresh();
            downstream.accept(null);
        };

        classSourcePanel.afterChange(
                this.afterChange);
        fieldSourcePanel.afterChange(
                this.afterChange);
        statementSourcePanel.afterChange(
                this.afterChange);
    }

    /**
     * Routes the class panel's free-text log to the query-log window.
     */
    public void log(
            Consumer<String> log) {

        classSourcePanel.log(log);
    }

    public void afterApplyField(
            Consumer<GeneratedFieldModel> consumer) {

        afterApplyField =
                consumer == null
                        ? field -> {}
                        : consumer;
    }

    public Object selected() {
        return selected;
    }

    public NodeSamplePanel samplePanel() {
        return samplePanel;
    }

    public PropertyDiscoveryPanel discoveryPanel() {
        return discoveryPanel;
    }

    public WikiProjectSeedPanel wikiProjectPanel() {
        return wikiProjectPanel;
    }

    public ExploreByExamplePanel explorePanel() {
        return explorePanel;
    }

    public CategorySeedPanel categoryPanel() {
        return categoryPanel;
    }

    public void onShowHelperTools(
            Runnable runnable) {

        onShowHelperTools =
                runnable == null
                        ? () -> {}
                        : runnable;
    }

    /** See {@link FieldSourcePanel#onReloadField}. */
    public void onReloadField(java.util.function.Consumer<String> consumer) {
        fieldSourcePanel.onReloadField(consumer);
    }

    public void onFieldAddedFromTool(
            Consumer<GeneratedFieldModel> consumer) {

        onFieldAddedFromTool =
                consumer == null
                        ? field -> {}
                        : consumer;
    }

    public void onGraphSelection(Consumer<Object> consumer) {
        graphConfigurationDiagram.onActivate(consumer);
    }

    /** The window's reusable selections, shared with tools that name or consume them. */
    public workbench.WorkbenchSelections selections() {
        return selections;
    }

    public void selectionEditor(SelectionViewerPanel editor) {
        if (selectionEditor != null) cardPanel.remove(selectionEditor);
        selectionEditor = editor;
        if (editor != null) cardPanel.add(editor, "selection");
    }

    public void domainStatus(Supplier<DomainOverviewPanel.Status> supplier) {
        domainOverview.status(supplier);
    }

    public void refreshDomainOverview() {
        domainOverview.refresh();
    }

    /** Opens the vocabulary/population editor in the Configuration area. */
    public void showSelections(wikidata.explore.model.Selection selection) {
        if (selectionEditor == null) return;
        this.selected = selection;
        kindBox.setEnabled(editingEnabled);
        kindHeader.setVisible(false);
        selectionEditor.edit(selection);
        ((CardLayout) cardPanel.getLayout()).show(cardPanel, "selection");
    }

    public JComponent helperTools() {
        return helperTabs;
    }

    public CachedPropertyViewablePanel propertyPanel() {
        return propertyPanel;
    }


    /** Opens the existing property catalogue from explanatory/configuration links. */
    public void showProperties() {
        showWikidataTool(propertyPanel);
    }

    private void showWikidataTool(Component tool) {
        helperTabs.setSelectedComponent(wikidataTools);
        wikidataTools.setSelectedComponent(tool);
    }

    /** Locks every mutation surface owned by the workbench, including helper tools
     * currently hosted in the separate Explorer window. Labels and containers remain
     * enabled so the selected configuration can still be read. */
    /** Names the owning model above a disabled editor, or hides the notice. */
    private void showImportedNotice(GeneratedClassModel clazz) {
        boolean imported = clazz != null && clazz.isImported();
        importedNotice.setVisible(imported);
        if (imported) {
            importedNotice.setText("Imported from " + clazz.importedFrom()
                    + " — owned there, and edited there. Shown here as it stands.");
        }
    }

    /** The class declaring this field, or null when it belongs to none. */
    private GeneratedClassModel owningClassOf(GeneratedFieldModel field) {
        return projectModel == null ? null : projectModel.declaringClass(field);
    }

    public void setEditingEnabled(boolean enabled) {
        editingEnabled = enabled;
        EditableComponents.setEditable(cardPanel, enabled);
        EditableComponents.setEditable(kindHeader, enabled);
        EditableComponents.setEditable(helperTabs, enabled);
    }

    public void edit(Object selected) {
        this.selected = selected;

        CardLayout layout =
                (CardLayout) cardPanel.getLayout();

        if (selected instanceof GeneratedClassModel clazz) {
            updatingKind = true;
            MembershipPattern pattern = MembershipPattern.of(clazz, projectModel);
            kindBox.setSelectedIndex(clazz.classKind() == wikidata.explore.model.ClassKind.AGGREGATE
                    ? 3 : clazz.reifiesStatements()
                    ? 1 : pattern == MembershipPattern.OWNED_COMPONENT ? 2 : 0);
            kindBox.setEnabled(editingEnabled);
            updatingKind = false;

            kindHeader.setVisible(true);

            if (clazz.classKind() == wikidata.explore.model.ClassKind.AGGREGATE) {
                aggregateClassPanel.edit(clazz);
                layout.show(cardPanel, "aggregate");
            } else if (clazz.reifiesStatements()) {
                statementSourcePanel.edit(clazz);
                layout.show(cardPanel, "statement");
            } else if (pattern == MembershipPattern.OWNED_COMPONENT) {
                ownedClassPanel.edit(clazz);
                layout.show(cardPanel, "owned");
            } else {
                classSourcePanel.edit(clazz);
                layout.show(cardPanel, "class");
            }
            // An imported class is not edited here at all — name, membership, identity
            // and everything else belong to the model that owns it. Locking the card
            // rather than each control means a new control cannot be added to these
            // editors and quietly escape the rule. Reapplied on every selection, so
            // this project's own classes stay editable.
            EditableComponents.setEditable(cardPanel, editingEnabled && !clazz.isImported());
            kindBox.setEnabled(editingEnabled && !clazz.isImported());
            showImportedNotice(clazz);
        } else if (selected
                instanceof GeneratedFieldModel field) {
            kindBox.setEnabled(editingEnabled);
            kindHeader.setVisible(false);
            showImportedNotice(null);
            fieldSourcePanel.edit(field);
            // A field of an imported class is readable but not editable: it is
            // configuration owned by the model the class comes from. Set on every
            // selection so moving to this project's own field restores editing.
            GeneratedClassModel fieldOwner = owningClassOf(field);
            EditableComponents.setEditable(fieldSourcePanel,
                    editingEnabled && (fieldOwner == null || !fieldOwner.isImported()));
            showImportedNotice(fieldOwner);
            layout.show(cardPanel, "field");
        } else if (selected instanceof wikidata.explore.model.Selection selection
                && selectionEditor != null) {
            kindBox.setEnabled(editingEnabled && !selection.isImported());
            kindHeader.setVisible(false);
            importedNotice.setVisible(selection.isImported());
            if (selection.isImported()) importedNotice.setText("Imported from "
                    + selection.importedFrom() + " — owned and edited there.");
            selectionEditor.edit(selection);
            EditableComponents.setEditable(selectionEditor,
                    editingEnabled && !selection.isImported());
            layout.show(cardPanel, "selection");
        } else if (selected == SingleRootClassModelPanel.ConfigurationSection.VOCABULARIES
                && selectionEditor != null) {
            kindBox.setEnabled(editingEnabled);
            kindHeader.setVisible(false);
            showImportedNotice(null);
            selectionEditor.edit(null);
            layout.show(cardPanel, "selection");
        } else if (selected instanceof GeneratedProjectModel) {
            kindBox.setEnabled(editingEnabled);
            kindHeader.setVisible(false);
            showImportedNotice(null);
            domainOverview.refresh();
            layout.show(cardPanel, "domain");
        } else {
            kindBox.setEnabled(editingEnabled);
            kindHeader.setVisible(false);
            showImportedNotice(null);
            layout.show(cardPanel, "empty");
        }

        discoveryPanel.refreshNodeTitle();
        graphPatternPanel.refreshPatterns();
        graphConfigurationDiagram.selection(selected);
        reusableSelectionsPanel.setVisible(!(selected instanceof GeneratedClassModel clazz)
                || clazz.classKind() != wikidata.explore.model.ClassKind.AGGREGATE);

        // The editors for the newly shown card are built (or rebuilt) enabled. The lock
        // is a property of THIS panel, so re-applying it here keeps every caller of
        // edit() from having to remember it — which is how a lock quietly springs open.
        if (!editingEnabled) setEditingEnabled(false);
    }

    /**
     * Commits the editor being left, then presents another model node.
     *
     * <p>A tree selection changes before its listener is notified. Keeping this
     * transition here means the workbench still knows which editor owns the pending
     * values; callers that replace the whole model can continue to use {@link #edit}
     * without writing stale controls into the old model.</p>
     */
    public void changeSelection(Object next) {
        if (changingSelection) {
            return;
        }
        changingSelection = true;
        try {
            if (selected != null && selected != next
                    && isLiveSelection(selected)) {
                applyEdits();
            }
            edit(next);
        } finally {
            changingSelection = false;
        }
    }

    /** Whether the selected editor still owns a declaration in the current model. */
    private boolean isLiveSelection(Object value) {
        if (value instanceof GeneratedClassModel clazz) {
            return projectModel.classes().contains(clazz);
        }
        if (value instanceof GeneratedFieldModel field) {
            return projectModel.declaringClass(field) != null;
        }
        if (value instanceof wikidata.explore.model.Selection selection) {
            return projectModel.selections().contains(selection);
        }
        return value == projectModel
                || value instanceof SingleRootClassModelPanel.ConfigurationSection;
    }

    /**
     * Detaches every editor without committing it.
     *
     * <p>This is the other half of a confirmed "discard changes" operation. A
     * domain load replaces the contents of the shared project object; leaving an
     * old editor selected lets the tree-selection event produced by that replacement
     * flush stale controls into the new domain. In particular, an old class name can
     * then collide with a vocabulary in the domain being loaded.</p>
     */
    void abandonEdits() {
        selected = null;
        classSourcePanel.edit(null);
        statementSourcePanel.edit(null);
        ownedClassPanel.edit(null);
        aggregateClassPanel.edit(null);
        fieldSourcePanel.edit(null);
        edit(null);
    }

    private String selectedNodeTitle() {
        GeneratedClassModel clazz = null;

        if (selected
                instanceof GeneratedClassModel selectedClass) {
            clazz = selectedClass;
        } else if (selected
                instanceof GeneratedFieldModel field) {
            clazz = projectModel.declaringClass(field);
        }

        if (clazz == null) {
            return null;
        }

        String qid =
                clazz.instanceMapping() == null
                        ? ""
                        : clazz.instanceMapping()
                               .sourceQid();

        return clazz.className()
                + (qid != null
                && !qid.isBlank()
                ? " (" + qid + ")"
                : "");
    }

    public void applyEdits() {
        if (selected instanceof GeneratedClassModel) {
            if (kindBox.getSelectedIndex() == 1) {
                statementSourcePanel.applyEdits();
            } else if (kindBox.getSelectedIndex() == 2) {
                ownedClassPanel.applyEdits();
            } else if (kindBox.getSelectedIndex() == 3) {
                aggregateClassPanel.applyEdits();
            } else {
                classSourcePanel.applyEdits();
            }
        }

        // A field editor keeps pending Swing values even after another tree node
        // is selected. Always flush it before save, generation or preview.
        fieldSourcePanel.applyEdits();
    }

    public RuleNode temporaryRuleNodeForSelected() {
        applyEdits();

        GeneratedClassModel sampled = samplingClass();
        if (sampled == null) {
            return null;
        }
        RuleNode node = RuleTreeCompiler.compileClass(sampled);
        // An evidence-derived kind declares no membership source — it is stamped from
        // P31 evidence, not queried — so the compiled node has no type to sample. Give
        // this node the rule's evidence type. It is TEMPORARY, for discovery and samples
        // only: the generation plan must keep compiling the class as it is, or a stamped
        // kind would silently become an extracted one.
        if (node != null && clean(node.sourceQid()).isEmpty()) {
            node.sourceQid(MembershipPattern.typeQid(sampled, projectModel));
        }
        return node;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * The class whose MEMBERS answer questions asked about the selection — which
     * properties occur, what a sample looks like.
     *
     * <p>For an owned component that is the class that OWNS it: a component has no
     * members to query, its instances being the owner's entities under the owner's QID.
     * Discovering "properties of Name" would sample nothing, while the properties its
     * fields load — P734, P735 — are properties of the Person one hop up.
     */
    private GeneratedClassModel samplingClass() {
        return samplingClass(projectModel, selected);
    }

    private static GeneratedClassModel samplingClass(
            GeneratedProjectModel model, Object selection) {
        if (model == null) return null;
        GeneratedClassModel clazz = null;
        if (selection instanceof GeneratedClassModel selectedClass) {
            clazz = selectedClass;
        } else if (selection instanceof GeneratedFieldModel field) {
            clazz = model.declaringClass(field);
        }
        if (clazz == null && selection == null) {
            return model.rootClass();
        }
        if (clazz == null) return null;
        GeneratedClassModel bearer =
                MembershipPattern.owningEntityClass(clazz, model);
        return bearer == null ? clazz : bearer;
    }

    public FieldSampleContext fieldSampleContextForSelected() {
        applyEdits();

        if (selected
                instanceof GeneratedFieldModel field) {
            return fieldSampleContext(projectModel, field);
        }

        return null;
    }

    static FieldSampleContext fieldSampleContext(
            GeneratedProjectModel model, GeneratedFieldModel field) {
        GeneratedClassModel owner = samplingClass(model, field);
        return field == null || owner == null ? null : new FieldSampleContext(
                    // A field is sampled on the population that carries it. Using
                    // the project root made Person.spouse in History compile the
                    // blank History root and issue BIND(wd: AS ?root).
                    owner,
                    field,
                    MembershipPattern.typeQid(owner, model));
    }

    public void useProperty(
            String pid,
            String label) {

        useProperty(pid, label, null);
    }

    public void useProperty(
            String pid,
            String label,
            RuleDirection direction) {

        useProperty(pid, label, direction, true);
    }

    private void useProperty(
            String pid,
            String label,
            RuleDirection direction,
            boolean showSampleTab) {

        GeneratedFieldModel field =
                createFieldForProperty(label);

        if (field == null) {
            return;
        }

        if (direction != null) {
            field.mapping().direction(direction);
            fieldSourcePanel.edit(field);
        }

        fieldSourcePanel.useProperty(pid, label);

        if (field.cardinality()
                == FieldCardinality.AUTO) {
            onShowHelperTools.run();
            // Discovery is a sequence: inspect several properties and add the useful
            // ones. Cardinality sampling may run without tearing the reader out of
            // that sequence. Direct property-catalogue actions retain the historical
            // behaviour of opening Sample so the result is visible immediately.
            if (showSampleTab) showWikidataTool(samplePanel);
            samplePanel.triggerFieldSample();
        } else {
            onFieldAddedFromTool.accept(field);
        }

        afterChange.accept(null);
    }

    /**
     * Adds a qualifier-backed field to the selected class.
     */
    public void useQualifier(
            String qualifierPid,
            String label) {

        GeneratedFieldModel field =
                createFieldForProperty(label);

        if (field == null) {
            return;
        }

        field.mapping().qualifierPid(
                RuleNode.cleanPid(qualifierPid));

        fieldSourcePanel.edit(field);
        onFieldAddedFromTool.accept(field);
        afterChange.accept(null);
    }

    private GeneratedFieldModel createFieldForProperty(
            String label) {

        GeneratedClassModel clazz =
                selected
                        instanceof GeneratedClassModel selectedClass
                        ? selectedClass
                        : projectModel.rootClass();

        if (clazz == null) {
            return null;
        }

        GeneratedFieldModel field =
                clazz.addField(
                        uniqueFieldName(clazz, label),
                        FieldType.AUTO,
                        FieldCardinality.AUTO);

        selected = field;
        fieldSourcePanel.edit(field);
        afterApplyField.accept(field);
        return field;
    }

    private static String uniqueFieldName(
            GeneratedClassModel clazz,
            String label) {

        String base = toFieldName(label);
        String result = base;

        for (int suffix = 2;
             fieldNameExists(clazz, result);
             suffix++) {
            result = base + suffix;
        }

        return result;
    }

    private static boolean fieldNameExists(
            GeneratedClassModel clazz,
            String name) {

        for (GeneratedFieldModel field
                : clazz.fields()) {
            if (field != null
                    && name.equals(field.name())) {
                return true;
            }
        }

        return false;
    }

    private static String toFieldName(
            String label) {

        if (label == null || label.isBlank()) {
            return "field";
        }

        StringBuilder result =
                new StringBuilder();

        for (String part
                : label.trim()
                       .split("[^A-Za-z0-9]+")) {
            if (part.isEmpty()) {
                continue;
            }

            if (result.isEmpty()) {
                result.append(
                        Character.toLowerCase(
                                part.charAt(0)));
            } else {
                result.append(
                        Character.toUpperCase(
                                part.charAt(0)));
            }

            result.append(part.substring(1));
        }

        if (result.isEmpty()
                || !Character.isJavaIdentifierStart(
                result.charAt(0))) {
            result.insert(0, "f");
        }

        return result.toString();
    }

    private void buildUi() {
        cardPanel.add(domainOverview, "domain");
        cardPanel.add(
                classSourcePanel,
                "class");
        cardPanel.add(
                statementSourcePanel,
                "statement");
        cardPanel.add(
                ownedClassPanel,
                "owned");
        cardPanel.add(aggregateClassPanel, "aggregate");
        cardPanel.add(
                fieldSourcePanel,
                "field");
        cardPanel.add(
                new JLabel(
                        "Select the class or a field."),
                "empty");

        kindHeader.add(
                new JLabel("Class kind:"));
        kindHeader.add(kindBox);
        kindHeader.setVisible(false);

        kindBox.addActionListener(
                event -> switchClassKind());

        fieldSourcePanel.setPropertyCache(
                propertyPanel.propertyCache());
        fieldSourcePanel.setProjectModel(
                projectModel);

        fieldSourcePanel.onSampleRequested(
                () -> {
                    onShowHelperTools.run();
                    showWikidataTool(samplePanel);
                    samplePanel.triggerFieldSample();
                });

        samplePanel.setNodeSupplier(
                this::temporaryRuleNodeForSelected);
        samplePanel.setFieldSampleSupplier(
                this::fieldSampleContextForSelected);

        // Success stays out of the reader's way; a failure does not, or the message
        // naming the unsampleable field lands on a tab nobody is looking at.
        samplePanel.onSampleFailed(() -> {
            onShowHelperTools.run();
            showWikidataTool(samplePanel);
        });
        samplePanel.onCardinalitySuggested(
                cardinality -> {
                    if (selected
                            instanceof GeneratedFieldModel field) {
                        field.cardinality(cardinality);
                        fieldSourcePanel.edit(field);
                        afterApplyField.accept(field);
                        afterChange.accept(null);
                    }
                });

        fieldSourcePanel.afterApplyField(
                field -> {
                    selected = field;
                    fieldSourcePanel.edit(field);
                    afterApplyField.accept(field);
                    afterChange.accept(null);
                });

        discoveryPanel.setNodeSupplier(
                this::temporaryRuleNodeForSelected);
        discoveryPanel.setNodeTitleSupplier(
                this::selectedNodeTitle);
        discoveryPanel.setApplyEdits(
                this::applyEdits);

        discoveryPanel.onAddField(
                property -> {
                    RuleDirection direction =
                            "incoming".equalsIgnoreCase(
                                    property.direction())
                                    ? RuleDirection.ITEM_TO_ROOT
                                    : RuleDirection.ROOT_TO_ITEM;

                    useProperty(
                            property.pid(),
                            property.label(),
                            direction,
                            false);
                    afterChange.accept(null);
                });

        discoveryPanel.onAddAllowedQid(
                qid -> {
                    if (selected
                            instanceof GeneratedFieldModel field) {
                        field.mapping()
                             .allowedQids()
                             .add(qid);
                        fieldSourcePanel.edit(field);
                        afterChange.accept(null);
                    }
                });

        discoveryPanel.onAddExcludedQid(
                qid -> {
                    if (selected
                            instanceof GeneratedFieldModel field) {
                        field.mapping()
                             .excludedQids()
                             .add(qid);
                        fieldSourcePanel.edit(field);
                        afterChange.accept(null);
                    }
                });

        wikidataTools.addTab(
                "Entity",
                explorePanel);
        wikidataTools.addTab(
                "Sample",
                samplePanel);
        wikidataTools.addTab(
                "Discover",
                discoveryPanel);
        wikidataTools.addTab(
                "Graph patterns",
                graphPatternPanel);
        wikidataTools.addTab(
                "Properties",
                propertyPanel);
        wikidataTools.addTab(
                "Entity relations",
                entityRelationPanel);

        wikipediaTools.addTab(
                "Categories",
                categoryPanel);
        wikipediaTools.addTab(
                "WikiProjects",
                wikiProjectPanel);

        helperTabs.addTab("Wikidata", wikidataTools);
        helperTabs.addTab("Wikipedia", wikipediaTools);

        JPanel config = new JPanel(new BorderLayout());
        JPanel configHeader = new JPanel();
        configHeader.setLayout(new BoxLayout(configHeader, BoxLayout.Y_AXIS));
        importedNotice.setVisible(false);
        importedNotice.setForeground(new java.awt.Color(120, 85, 20));
        importedNotice.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        configHeader.add(importedNotice);
        configHeader.add(kindHeader);
        reusableSelectionsPanel.add(new SelectionsButton(selections).useProperties(
                "Use selected property",
                SelectionsButton.Cardinality.SINGLE,
                values -> useSelectedProperty(values.getFirst())));
        configHeader.add(reusableSelectionsPanel);
        config.add(configHeader, BorderLayout.NORTH);
        config.add(
                cardPanel,
                BorderLayout.CENTER);

        config.add(graphConfigurationDiagram, BorderLayout.SOUTH);

        add(config, BorderLayout.CENTER);
    }

    private boolean canUseSelectedProperty() {
        return (selected instanceof GeneratedFieldModel
                || selected instanceof GeneratedClassModel clazz
                && !clazz.reifiesStatements() && !clazz.ownedClass()
                && clazz.classKind() != wikidata.explore.model.ClassKind.AGGREGATE)
                && editingEnabled;
    }

    private void useSelectedProperty(WorkbenchSelections.Property property) {
        if (property == null || !canUseSelectedProperty()) return;
        if (selected instanceof GeneratedFieldModel) {
            fieldSourcePanel.useProperty(property.pid(), property.label());
        } else if (selected instanceof GeneratedClassModel clazz
                && !clazz.reifiesStatements() && !clazz.ownedClass()) {
            classSourcePanel.usePopulationProperty(property.pid(), property.label());
        }
    }

    private void switchClassKind() {
        if (updatingKind
                || !(selected
                instanceof GeneratedClassModel clazz)) {
            return;
        }

        CardLayout layout =
                (CardLayout) cardPanel.getLayout();

        int kind = kindBox.getSelectedIndex();
        boolean toStatement = kind == 1;
        reusableSelectionsPanel.setVisible(kind != 3);

        if (kind == 3) {
            if (clazz.reifiesStatements()) statementSourcePanel.applyEdits();
            else if (clazz.ownedClass()) ownedClassPanel.applyEdits();
            else if (clazz.classKind() != wikidata.explore.model.ClassKind.AGGREGATE) {
                classSourcePanel.applyEdits();
            }
            clazz.statementSource(null);
            clazz.classKind(wikidata.explore.model.ClassKind.AGGREGATE);
            if (clazz.aggregateSource() == null) {
                clazz.aggregateSource(new wikidata.explore.model.AggregateClassSource());
            }
            aggregateClassPanel.edit(clazz);
            layout.show(cardPanel, "aggregate");
            afterChange.accept(null);
            return;
        }

        if (kind == 2) {
            if (clazz.classKind() == wikidata.explore.model.ClassKind.AGGREGATE) {
                aggregateClassPanel.applyEdits();
                clazz.aggregateSource(null);
            }
            if (!clazz.ownedClass()) {
                int answer = JOptionPane.showConfirmDialog(this,
                        "Make " + clazz.className() + " an Owned class?\n\n"
                                + "It will no longer have an independent Wikidata source. "
                                + "Its instances will be created by ENTITY fields that "
                                + "target it.",
                        "Owned class", JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.QUESTION_MESSAGE);
                if (answer != JOptionPane.OK_OPTION) {
                    updatingKind = true;
                    kindBox.setSelectedIndex(clazz.reifiesStatements() ? 1 : 0);
                    updatingKind = false;
                    return;
                }
                classSourcePanel.applyEdits();
                clazz.ownedClass(true);
            }
            ownedClassPanel.edit(clazz);
            layout.show(cardPanel, "owned");
            afterChange.accept(null);
            return;
        }

        if (toStatement) {
            if (clazz.classKind() == wikidata.explore.model.ClassKind.AGGREGATE) {
                aggregateClassPanel.applyEdits();
                clazz.aggregateSource(null);
                clazz.classKind(wikidata.explore.model.ClassKind.SOURCE);
            }
            if (clazz.ownedClass()) {
                clazz.ownedClass(false);
            }
            if (!clazz.reifiesStatements()) {
                classSourcePanel.applyEdits();

                String sourceClass =
                        projectModel.classes()
                                    .stream()
                                    .map(
                                            GeneratedClassModel::className)
                                    .filter(
                                            name -> name != null
                                                    && !name.isBlank()
                                                    && !name.equals(
                                                    clazz.className()))
                                    .findFirst()
                                    .orElse("");

                // Switching class kind now writes the explicit source object.
                // It no longer creates a hidden dependency between the legacy
                // statementSourceClass field and instanceMapping.propertyPid.
                //
                // The source class is OPTIONAL (subjects can be discovered from the
                // statement property alone), so a single-class model still becomes a
                // valid statement class — a blank source class yields a property-only
                // source, refined in the statement panel. Previously a blank source
                // produced a null here, so the kind couldn't switch at all.
                clazz.statementSource(
                        new StatementClassSource(
                                sourceClass,
                                "P1411"));
                // Switching the class kind is an explicit creation operation.
                // Persist the current proposal now; runtime code never infers it.
                wikidata.explore.model.StatementCanonicalDefaults
                        .replaceWithSuggestion(clazz);
            }

            statementSourcePanel.edit(clazz);
            layout.show(cardPanel, "statement");
        } else {
            if (clazz.classKind() == wikidata.explore.model.ClassKind.AGGREGATE) {
                aggregateClassPanel.applyEdits();
                clazz.aggregateSource(null);
                clazz.classKind(wikidata.explore.model.ClassKind.SOURCE);
            }
            if (clazz.ownedClass()) {
                clazz.ownedClass(false);
            }
            if (clazz.reifiesStatements()) {
                statementSourcePanel.applyEdits();
                clazz.statementSource(null);
                clazz.canonical(null);
            }

            classSourcePanel.edit(clazz);
            layout.show(cardPanel, "class");
        }

        afterChange.accept(null);
    }


    /** Releases helper tools that hold more than memory. */
    @Override public void close() {
        entityRelationPanel.close();
    }
}
