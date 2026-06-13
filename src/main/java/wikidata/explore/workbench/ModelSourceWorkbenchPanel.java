package wikidata.explore.workbench;

import wikidata.explore.model.FieldSampleContext;
import wikidata.explore.rule.RuleTreeCompiler;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.model.FieldCardinality;
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

    private final CachedPropertyQuizablePanel propertyPanel =
            new CachedPropertyQuizablePanel();

    private Object selected;

    private Consumer<Void> afterChange = v -> {};
    private Consumer<GeneratedFieldModel> afterApplyField = f -> {};

    public ModelSourceWorkbenchPanel(GeneratedProjectModel projectModel) {
        super(new BorderLayout(4, 4));
        this.projectModel = projectModel;
        buildUi();
    }

    public void setQueryRunner(SwingQueryRunner queryRunner) {
        classSourcePanel.setQueryRunner(queryRunner);
        samplePanel.setQueryRunner(queryRunner);
        discoveryPanel.setQueryRunner(queryRunner);
        wikiProjectPanel.setQueryRunner(queryRunner);
    }

    public void afterChange(Consumer<Void> afterChange) {
        this.afterChange =
                afterChange == null ? v -> {} : afterChange;

        classSourcePanel.afterChange(this.afterChange);
        fieldSourcePanel.afterChange(this.afterChange);
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

    public CachedPropertyQuizablePanel propertyPanel() {
        return propertyPanel;
    }

    public void edit(Object selected) {
        this.selected = selected;

        CardLayout layout =
                (CardLayout) cardPanel.getLayout();

        if (selected instanceof GeneratedClassModel c) {
            classSourcePanel.edit(c);
            layout.show(cardPanel, "class");
        } else if (selected instanceof GeneratedFieldModel f) {
            fieldSourcePanel.edit(f);
            layout.show(cardPanel, "field");
        } else {
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
            classSourcePanel.applyEdits();
        } else if (selected instanceof GeneratedFieldModel) {
            fieldSourcePanel.applyEdits();
        }
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
        if (selected instanceof GeneratedFieldModel f) {
            fieldSourcePanel.useProperty(pid, label);

            if (f.cardinality() == FieldCardinality.AUTO) {
                helperTabs.setSelectedComponent(samplePanel);
                samplePanel.triggerFieldSample();
            }

            afterChange.accept(null);
        }
    }

    private void buildUi() {
        cardPanel.add(classSourcePanel, "class");
        cardPanel.add(fieldSourcePanel, "field");
        cardPanel.add(new JLabel("Select the class or a field."), "empty");

        fieldSourcePanel.setPropertyCache(propertyPanel.propertyCache());
        fieldSourcePanel.setProjectModel(projectModel);

        fieldSourcePanel.onSampleRequested(() -> {
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
            useProperty(p.pid(), p.label());
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

        helperTabs.addTab("Sample", samplePanel);
        helperTabs.addTab("Discover", discoveryPanel);
        helperTabs.addTab("WikiProject", wikiProjectPanel);
        helperTabs.addTab("Properties", propertyPanel);

        JSplitPane split =
                new JSplitPane(
                        JSplitPane.VERTICAL_SPLIT,
                        cardPanel,
                        helperTabs);

        split.setResizeWeight(0.38);
        split.setContinuousLayout(true);

        add(split, BorderLayout.CENTER);
    }
}