package wikidata.explore.workbench;

import wikidata.explore.model.FieldSampleContext;
import wikidata.explore.rule.RuleTreeCompiler;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.swing.SwingQueryRunner;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

public class ModelSourceWorkbenchPanel extends JPanel {

    private final GeneratedProjectModel projectModel;

    private final ClassSourcePanel classSourcePanel =
            new ClassSourcePanel();

    private final FieldSourcePanel fieldSourcePanel =
            new FieldSourcePanel();

    // Statement-reification classes (statement-id identity) get their own panel,
    // distinct from the qid-identity ClassSourcePanel; a kind toggle switches.
    private final StatementSourcePanel statementSourcePanel =
            new StatementSourcePanel();
    private final JComboBox<String> kindBox =
            new JComboBox<>(new String[]{"Source class", "Statement class"});
    private final JPanel kindHeader =
            new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    private boolean updatingKind = false;

    private final JPanel cardPanel =
            new JPanel(new CardLayout());

    private final JTabbedPane helperTabs =
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

    private final CachedPropertyQuizablePanel propertyPanel =
            new CachedPropertyQuizablePanel();

    private Object selected;

    private Consumer<Void> afterChange = v -> {};
    private Consumer<GeneratedFieldModel> afterApplyField = f -> {};
    // Fired ONLY when a field is added via a tool (Discover/Properties), so the
    // main window can come forward — unlike afterApplyField, which also fires on
    // the implicit applyEdits before a query (that must not steal focus).
    private Consumer<GeneratedFieldModel> onFieldAddedFromTool = f -> {};

    // Raises the Explorer window when a config action jumps to a helper tab
    // (Sample/Discover), since those tabs now live in that separate window.
    private Runnable onShowHelperTools = () -> {};

    public ModelSourceWorkbenchPanel(GeneratedProjectModel projectModel) {
        super(new BorderLayout(4, 4));
        this.projectModel = projectModel;
        classSourcePanel.baseClassCandidates(() ->
                projectModel.classes().stream()
                        .map(GeneratedClassModel::className)
                        .toList());
        statementSourcePanel.sourceClassCandidates(() ->
                projectModel.classes().stream()
                        .map(GeneratedClassModel::className)
                        .toList());
        statementSourcePanel.setProjectModel(projectModel);
        buildUi();
    }

    public void setQueryRunner(SwingQueryRunner queryRunner) {
        classSourcePanel.setQueryRunner(queryRunner);
        fieldSourcePanel.setQueryRunner(queryRunner);
        samplePanel.setQueryRunner(queryRunner);
        discoveryPanel.setQueryRunner(queryRunner);
        wikiProjectPanel.setQueryRunner(queryRunner);
        explorePanel.setQueryRunner(queryRunner);
        categoryPanel.setQueryRunner(queryRunner);
    }

    public void afterChange(Consumer<Void> afterChange) {
        this.afterChange =
                afterChange == null ? v -> {} : afterChange;

        classSourcePanel.afterChange(this.afterChange);
        fieldSourcePanel.afterChange(this.afterChange);
        statementSourcePanel.afterChange(this.afterChange);
    }

    /** Routes the sub-panels' free-text log (e.g. the class panel's Wikidata API
     *  requests) to a sink — wire it to the query-log window. */
    public void log(Consumer<String> log) {
        classSourcePanel.log(log);
    }

    public void afterApplyField(
            Consumer<GeneratedFieldModel> afterApplyField) {

        this.afterApplyField =
                afterApplyField == null ? f -> {} : afterApplyField;
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

    public void onShowHelperTools(Runnable r) {
        this.onShowHelperTools = r == null ? () -> {} : r;
    }

    public void onFieldAddedFromTool(Consumer<GeneratedFieldModel> c) {
        this.onFieldAddedFromTool = c == null ? f -> {} : c;
    }

    /** The discovery/lookup tools, hosted in a separate Explorer window so the
     *  main frame stays focused on the domain + class/field configuration. */
    public JComponent helperTools() {
        return helperTabs;
    }

    public CachedPropertyQuizablePanel propertyPanel() {
        return propertyPanel;
    }

    public void edit(Object selected) {
        this.selected = selected;

        CardLayout layout =
                (CardLayout) cardPanel.getLayout();

        if (selected instanceof GeneratedClassModel c) {
            updatingKind = true;
            kindBox.setSelectedIndex(c.reifiesStatements() ? 1 : 0);
            updatingKind = false;
            kindHeader.setVisible(true);
            if (c.reifiesStatements()) {
                statementSourcePanel.edit(c);
                layout.show(cardPanel, "statement");
            } else {
                classSourcePanel.edit(c);
                layout.show(cardPanel, "class");
            }
        } else if (selected instanceof GeneratedFieldModel f) {
            kindHeader.setVisible(false);
            fieldSourcePanel.edit(f);
            layout.show(cardPanel, "field");
        } else {
            kindHeader.setVisible(false);
            layout.show(cardPanel, "empty");
        }

        discoveryPanel.refreshNodeTitle();
    }

    /**
     * Read-only title for the discovery panel — no applyEdits, so it is
     * safe to call during a tree selection (see PropertyDiscoveryPanel's
     * nodeTitleSupplier note).
     */
    private String selectedNodeTitle() {
        GeneratedClassModel cls = null;

        if (selected instanceof GeneratedClassModel c) {
            cls = c;
        } else if (selected instanceof GeneratedFieldModel) {
            cls = projectModel.rootClass();
        }

        if (cls == null) {
            return null;
        }

        String qid = cls.instanceMapping() == null
                ? ""
                : cls.instanceMapping().sourceQid();

        return cls.className()
                + (qid != null && !qid.isBlank() ? " (" + qid + ")" : "");
    }

    public void applyEdits() {
        if (selected instanceof GeneratedClassModel) {
            if (kindBox.getSelectedIndex() == 1) {
                statementSourcePanel.applyEdits();
            } else {
                classSourcePanel.applyEdits();
            }
        }
        // ALWAYS flush the field editor too — its pending edits live in the Swing
        // controls until apply(), so a user who sets a field then selects the class
        // (e.g. to read the derived recipe) before Save would otherwise lose them.
        // apply() no-ops when no field is loaded.
        fieldSourcePanel.applyEdits();
    }

    public RuleNode temporaryRuleNodeForSelected() {
        applyEdits();

        if (selected instanceof GeneratedClassModel c) {
            return RuleTreeCompiler.compileClass(c);
        }

        if (selected instanceof GeneratedFieldModel) {
            return RuleTreeCompiler.compileClass(projectModel.rootClass());
        }

        return null;
    }

    public FieldSampleContext fieldSampleContextForSelected() {
        applyEdits();

        if (selected instanceof GeneratedFieldModel f) {
            return new FieldSampleContext(projectModel.rootClass(), f);
        }

        return null;
    }

    public void useProperty(String pid, String label) {
        useProperty(pid, label, null);
    }

    public void useProperty(String pid, String label, RuleDirection direction) {
        // "Add Field" in Discover (and picking a property in Properties) always
        // creates a NEW field for that property. It must never hijack whatever
        // field happens to be selected: clicking "Add Field" while the
        // neighbours field was selected used to overwrite its property (P47 ->
        // P1813), turning it into a collection whose required flag is ignored.
        GeneratedFieldModel f = createFieldForProperty(label);
        if (f == null) {
            return;
        }

        // An incoming discovered property (e.g. stars whose P59 = this
        // constellation) becomes an incoming edge; outgoing stays outgoing.
        if (direction != null) {
            f.mapping().direction(direction);
            fieldSourcePanel.edit(f);
        }

        fieldSourcePanel.useProperty(pid, label);

        if (f.cardinality() == FieldCardinality.AUTO) {
            // Needs a sample to settle cardinality — stay in the Explorer's
            // Sample tab rather than yanking to the main window.
            onShowHelperTools.run();
            helperTabs.setSelectedComponent(samplePanel);
            samplePanel.triggerFieldSample();
        } else {
            // Fully configured — surface it in the main config window.
            onFieldAddedFromTool.accept(f);
        }

        afterChange.accept(null);
    }

    /** Add a NEW field to the selected class sourced from a statement QUALIFIER
     *  (the qualifier analog of {@link #useProperty}) — e.g. edition ← P805 on a
     *  statement-reification class. Sets the qualifier PID and opens the field
     *  editor pre-filled; the user finishes type/class there. */
    public void useQualifier(String qualifierPid, String label) {
        GeneratedFieldModel f = createFieldForProperty(label);
        if (f == null) {
            return;
        }
        f.mapping().qualifierPid(wikidata.explore.rule.RuleNode.cleanPid(qualifierPid));
        fieldSourcePanel.edit(f);        // reflect the qualifier PID in the editor
        onFieldAddedFromTool.accept(f);  // surface it in the main config window
        afterChange.accept(null);
    }

    // Adds a fresh AUTO field (named after the property) to the selected
    // class, or the root class if a field (or nothing) is selected, then
    // selects it so the subsequent useProperty applies to it.
    private GeneratedFieldModel createFieldForProperty(String label) {
        GeneratedClassModel cls =
                selected instanceof GeneratedClassModel c
                        ? c
                        : projectModel.rootClass();
        if (cls == null) {
            return null;
        }

        GeneratedFieldModel f = cls.addField(
                uniqueFieldName(cls, label), FieldType.AUTO, FieldCardinality.AUTO);

        selected = f;
        fieldSourcePanel.edit(f);
        afterApplyField.accept(f); // rebuild the class tree and select the node
        return f;
    }

    private static String uniqueFieldName(GeneratedClassModel cls, String label) {
        String base = toFieldName(label);
        String name = base;
        for (int n = 2; fieldNameExists(cls, name); n++) {
            name = base + n;
        }
        return name;
    }

    private static boolean fieldNameExists(GeneratedClassModel cls, String name) {
        for (GeneratedFieldModel f : cls.fields()) {
            if (f != null && name.equals(f.name())) {
                return true;
            }
        }
        return false;
    }

    // "named after" / "shares-border-with" -> a camelCase Java identifier.
    private static String toFieldName(String label) {
        if (label == null || label.isBlank()) {
            return "field";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : label.trim().split("[^A-Za-z0-9]+")) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.isEmpty()) {
                sb.append(Character.toLowerCase(part.charAt(0)));
            } else {
                sb.append(Character.toUpperCase(part.charAt(0)));
            }
            sb.append(part.substring(1));
        }
        if (sb.isEmpty() || !Character.isJavaIdentifierStart(sb.charAt(0))) {
            sb.insert(0, "f");
        }
        return sb.toString();
    }

    private void buildUi() {
        cardPanel.add(classSourcePanel, "class");
        cardPanel.add(statementSourcePanel, "statement");
        cardPanel.add(fieldSourcePanel, "field");
        cardPanel.add(new JLabel("Select the class or a field."), "empty");

        // Kind toggle: a class is either a SOURCE class (qid identity, upsert) or a
        // STATEMENT class (statement-id identity + dedup). Switching converts the
        // model and swaps the config panel. Hidden unless a class is selected.
        kindHeader.add(new JLabel("Class kind:"));
        kindHeader.add(kindBox);
        kindHeader.setVisible(false);
        kindBox.addActionListener(e -> {
            if (updatingKind || !(selected instanceof GeneratedClassModel c)) {
                return;
            }
            CardLayout layout = (CardLayout) cardPanel.getLayout();
            boolean toStatement = kindBox.getSelectedIndex() == 1;
            if (toStatement) {
                // Flush any pending source edits, then seed the statement source
                // from the domain root so the reify has a "reify from" default.
                if (!c.reifiesStatements()) {
                    classSourcePanel.applyEdits();
                    String seed = projectModel.classes().stream()
                            .map(GeneratedClassModel::className)
                            .filter(n -> n != null && !n.isBlank()
                                    && !n.equals(c.className()))
                            .findFirst().orElse("");
                    c.statementSourceClass(seed);
                }
                statementSourcePanel.edit(c);
                layout.show(cardPanel, "statement");
            } else {
                if (c.reifiesStatements()) {
                    statementSourcePanel.applyEdits();
                    c.statementSourceClass("");
                }
                classSourcePanel.edit(c);
                layout.show(cardPanel, "class");
            }
            afterChange.accept(null);
        });

        fieldSourcePanel.setPropertyCache(propertyPanel.propertyCache());
        fieldSourcePanel.setProjectModel(projectModel);

        fieldSourcePanel.onSampleRequested(() -> {
            onShowHelperTools.run();
            helperTabs.setSelectedComponent(samplePanel);
            samplePanel.triggerFieldSample();
        });

        samplePanel.setNodeSupplier(this::temporaryRuleNodeForSelected);
        samplePanel.setFieldSampleSupplier(this::fieldSampleContextForSelected);

        samplePanel.onCardinalitySuggested(cardinality -> {
            if (selected instanceof GeneratedFieldModel f) {
                f.cardinality(cardinality);
                fieldSourcePanel.edit(f);
                afterApplyField.accept(f);
                afterChange.accept(null);
            }
        });

        fieldSourcePanel.afterApplyField(f -> {
            selected = f;
            fieldSourcePanel.edit(f);
            afterApplyField.accept(f);
            afterChange.accept(null);
        });

        discoveryPanel.setNodeSupplier(this::temporaryRuleNodeForSelected);
        discoveryPanel.setNodeTitleSupplier(this::selectedNodeTitle);
        discoveryPanel.setApplyEdits(this::applyEdits);

        discoveryPanel.onAddField(p -> {
            RuleDirection dir = "incoming".equalsIgnoreCase(p.direction())
                    ? RuleDirection.ITEM_TO_ROOT
                    : RuleDirection.ROOT_TO_ITEM;
            useProperty(p.pid(), p.label(), dir);
            afterChange.accept(null);
        });

        discoveryPanel.onAddAllowedQid(qid -> {
            if (selected instanceof GeneratedFieldModel f) {
                f.mapping().allowedQids().add(qid);
                fieldSourcePanel.edit(f);
                afterChange.accept(null);
            }
        });

        discoveryPanel.onAddExcludedQid(qid -> {
            if (selected instanceof GeneratedFieldModel f) {
                f.mapping().excludedQids().add(qid);
                fieldSourcePanel.edit(f);
                afterChange.accept(null);
            }
        });

        propertyPanel.onPropertySelected(property -> {
            useProperty(property.pid(), property.getName());
            afterChange.accept(null);
        });

        helperTabs.addTab("Explore", explorePanel);
        helperTabs.addTab("Sample", samplePanel);
        helperTabs.addTab("Discover", discoveryPanel);
        helperTabs.addTab("WikiProject", wikiProjectPanel);
        helperTabs.addTab("Category", categoryPanel);
        helperTabs.addTab("Properties", propertyPanel);

        // The helper tabs live in a separate Explorer window (see
        // helperTools()); this panel hosts only the class/field config.
        // The kind toggle sits above the config card (visible for classes).
        JPanel config = new JPanel(new BorderLayout());
        config.add(kindHeader, BorderLayout.NORTH);
        config.add(cardPanel, BorderLayout.CENTER);
        add(config, BorderLayout.CENTER);
    }
}