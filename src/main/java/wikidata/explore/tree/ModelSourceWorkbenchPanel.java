package wikidata.explore.tree;

import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

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

    private Consumer<GeneratedFieldModel> afterApplyField = f -> {};

    public ModelSourceWorkbenchPanel(GeneratedProjectModel projectModel) {
        super(new BorderLayout(4, 4));
        this.projectModel = projectModel;
        buildUi();
    }

    public void log(Consumer<String> log) {
        classSourcePanel.log(log);
    }

    public void setClient(WikidataSparqlClient client) {
        classSourcePanel.setClient(client);

        samplePanel.setClient(client);
        discoveryPanel.setClient(client);
        wikiProjectPanel.setSparqlClient(client);
    }

    public void setApiClient(WikidataApiClient apiClient) {
        classSourcePanel.setApiClient(apiClient);
    }

    public void afterChange(Consumer<Void> afterChange) {
        Consumer<Void> afterChange1 =
                afterChange == null ? v -> {} : afterChange;

        classSourcePanel.afterChange(afterChange1);
        fieldSourcePanel.afterChange(afterChange1);
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
    }

    public RuleNode temporaryRuleNodeForSelected() {
        if (selected instanceof GeneratedClassModel c) {
            return RuleTreeCompiler.compileClass(c);
        }

        if (selected instanceof GeneratedFieldModel) {
            return RuleTreeCompiler.compileClass(projectModel.rootClass());
        }

        return null;
    }

    public FieldSampleContext fieldSampleContextForSelected() {
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

        samplePanel.onCardinalitySuggested(cardinality -> {
            if (selected instanceof GeneratedFieldModel f) {
                f.cardinality(cardinality);
                fieldSourcePanel.edit(f);
                afterApplyField.accept(f);
            }
        });

        fieldSourcePanel.afterApplyField(f -> {
            selected = f;
            fieldSourcePanel.edit(f);
            afterApplyField.accept(f);
        });

        discoveryPanel.setNodeSupplier(this::temporaryRuleNodeForSelected);
        discoveryPanel.onAddField(p -> useProperty(p.pid(), p.label()));
        discoveryPanel.onAddAllowedQid(qid -> {
            if (selected instanceof GeneratedFieldModel f) {
                f.mapping().allowedQids().add(qid);
                fieldSourcePanel.edit(f);
            }
        });
        discoveryPanel.onAddExcludedQid(qid -> {
            if (selected instanceof GeneratedFieldModel f) {
                f.mapping().excludedQids().add(qid);
                fieldSourcePanel.edit(f);
            }
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