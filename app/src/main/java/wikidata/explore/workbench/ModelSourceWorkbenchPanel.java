package wikidata.explore.workbench;

import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldSampleContext;
import wikidata.explore.model.FieldType;
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

/**
 * Hosts the mutually exclusive editors for qid-identity source classes,
 * statement-reification classes and fields.
 */
public class ModelSourceWorkbenchPanel extends JPanel {

    private final GeneratedProjectModel projectModel;

    private final ClassSourcePanel classSourcePanel =
            new ClassSourcePanel();
    private final FieldSourcePanel fieldSourcePanel =
            new FieldSourcePanel();
    private final StatementSourcePanel statementSourcePanel =
            new StatementSourcePanel();
    private final OwnedClassPanel ownedClassPanel;

    private final JComboBox<String> kindBox =
            new JComboBox<>(
                    new String[]{
                            "Source class",
                            "Statement class",
                            "Owned class"
                    });

    private final JPanel kindHeader =
            new JPanel(
                    new FlowLayout(
                            FlowLayout.LEFT,
                            6,
                            2));

    private boolean updatingKind = false;

    private final JPanel cardPanel =
            new JPanel(new CardLayout());

    private final JTabbedPane helperTabs =
            new JTabbedPane(
                    JTabbedPane.TOP,
                    JTabbedPane.SCROLL_TAB_LAYOUT);

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

    private Object selected;

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
        this.ownedClassPanel = new OwnedClassPanel(projectModel);
        this.ownedClassPanel.afterChange(ignored -> afterChange.accept(null));

        classSourcePanel.baseClassCandidates(
                () -> projectModel.classes()
                                  .stream()
                                  .map(
                                          GeneratedClassModel::className)
                                  .toList());

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
    }

    public void afterChange(
            Consumer<Void> afterChange) {

        this.afterChange =
                afterChange == null
                        ? ignored -> {}
                        : afterChange;

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

    public JComponent helperTools() {
        return helperTabs;
    }

    public CachedPropertyViewablePanel propertyPanel() {
        return propertyPanel;
    }

    /** Opens the existing property catalogue from explanatory/configuration links. */
    public void showProperties() {
        helperTabs.setSelectedComponent(propertyPanel);
    }

    public void edit(Object selected) {
        this.selected = selected;

        CardLayout layout =
                (CardLayout) cardPanel.getLayout();

        if (selected instanceof GeneratedClassModel clazz) {
            updatingKind = true;
            MembershipPattern pattern = MembershipPattern.of(clazz, projectModel);
            kindBox.setSelectedIndex(clazz.reifiesStatements()
                    ? 1 : pattern == MembershipPattern.OWNED_COMPONENT ? 2 : 0);
            kindBox.setEnabled(true);
            updatingKind = false;

            kindHeader.setVisible(true);

            if (clazz.reifiesStatements()) {
                statementSourcePanel.edit(clazz);
                layout.show(cardPanel, "statement");
            } else if (pattern == MembershipPattern.OWNED_COMPONENT) {
                ownedClassPanel.edit(clazz);
                layout.show(cardPanel, "owned");
            } else {
                classSourcePanel.edit(clazz);
                layout.show(cardPanel, "class");
            }
        } else if (selected
                instanceof GeneratedFieldModel field) {
            kindBox.setEnabled(true);
            kindHeader.setVisible(false);
            fieldSourcePanel.edit(field);
            layout.show(cardPanel, "field");
        } else {
            kindBox.setEnabled(true);
            kindHeader.setVisible(false);
            layout.show(cardPanel, "empty");
        }

        discoveryPanel.refreshNodeTitle();
    }

    private String selectedNodeTitle() {
        GeneratedClassModel clazz = null;

        if (selected
                instanceof GeneratedClassModel selectedClass) {
            clazz = selectedClass;
        } else if (selected
                instanceof GeneratedFieldModel) {
            clazz = projectModel.rootClass();
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
        GeneratedClassModel clazz = null;
        if (selected instanceof GeneratedClassModel selectedClass) {
            clazz = selectedClass;
        } else if (selected instanceof GeneratedFieldModel field) {
            clazz = declaringClass(field);
        }
        if (clazz == null) {
            return projectModel.rootClass();
        }
        GeneratedClassModel bearer =
                MembershipPattern.owningEntityClass(clazz, projectModel);
        return bearer == null ? clazz : bearer;
    }

    /** The class a field is declared on — a field's questions are about its own class,
     *  which is not the root once a project has more than one. */
    private GeneratedClassModel declaringClass(GeneratedFieldModel field) {
        for (GeneratedClassModel clazz : projectModel.classes()) {
            if (clazz != null && clazz.fields().contains(field)) {
                return clazz;
            }
        }
        return projectModel.rootClass();
    }

    public FieldSampleContext fieldSampleContextForSelected() {
        applyEdits();

        if (selected
                instanceof GeneratedFieldModel field) {
            return new FieldSampleContext(
                    projectModel.rootClass(),
                    field);
        }

        return null;
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
            helperTabs.setSelectedComponent(
                    samplePanel);
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
        cardPanel.add(
                classSourcePanel,
                "class");
        cardPanel.add(
                statementSourcePanel,
                "statement");
        cardPanel.add(
                ownedClassPanel,
                "owned");
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
                    helperTabs.setSelectedComponent(
                            samplePanel);
                    samplePanel.triggerFieldSample();
                });

        samplePanel.setNodeSupplier(
                this::temporaryRuleNodeForSelected);
        samplePanel.setFieldSampleSupplier(
                this::fieldSampleContextForSelected);

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
                            direction);
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

        propertyPanel.onPropertySelected(
                property -> {
                    useProperty(
                            property.pid(),
                            property.getName());
                    afterChange.accept(null);
                });

        helperTabs.addTab(
                "Explore",
                explorePanel);
        helperTabs.addTab(
                "Sample",
                samplePanel);
        helperTabs.addTab(
                "Discover",
                discoveryPanel);
        helperTabs.addTab(
                "WikiProject",
                wikiProjectPanel);
        helperTabs.addTab(
                "Category",
                categoryPanel);
        helperTabs.addTab(
                "Properties",
                propertyPanel);

        JPanel config =
                new JPanel(new BorderLayout());
        config.add(
                kindHeader,
                BorderLayout.NORTH);
        config.add(
                cardPanel,
                BorderLayout.CENTER);

        add(config, BorderLayout.CENTER);
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

        if (kind == 2) {
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

}
