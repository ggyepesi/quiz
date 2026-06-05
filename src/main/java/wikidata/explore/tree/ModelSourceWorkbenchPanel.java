package wikidata.explore.tree;

import wikidata.WikidataSparqlClient;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Middle panel.
 *
 * Switches between:
 * - ClassSourcePanel for selected root class
 * - FieldSourcePanel for selected field
 *
 * Also supplies Sample with either a class context or field context.
 */
public class ModelSourceWorkbenchPanel extends JPanel {

    private final GeneratedProjectModel projectModel;

    private final ClassSourcePanel classSourcePanel =
            new ClassSourcePanel();

    private final FieldSourcePanel fieldSourcePanel =
            new FieldSourcePanel();

    private final JPanel cardPanel =
            new JPanel(new CardLayout());

    private final JTabbedPane helperTabs =
            new JTabbedPane();

    private final NodeSamplePanel samplePanel =
            new NodeSamplePanel();

    private final NodePropertyDiscoveryPanel discoveryPanel =
            new NodePropertyDiscoveryPanel();

    private final WikiProjectSeedPanel wikiProjectPanel =
            new WikiProjectSeedPanel();

    private final CachedPropertyQuizablePanel propertyPanel =
            new CachedPropertyQuizablePanel();

    private Object selected;

    private Consumer<Void> afterChange =
            v -> {
            };

    public ModelSourceWorkbenchPanel(GeneratedProjectModel projectModel) {
        super(new BorderLayout(4, 4));
        this.projectModel = projectModel;
        buildUi();
    }

    public void setClient(WikidataSparqlClient client) {
        samplePanel.setClient(client);
        discoveryPanel.setClient(client);
        wikiProjectPanel.setSparqlClient(client);
    }

    public void afterChange(Consumer<Void> afterChange) {
        this.afterChange =
                afterChange == null ? v -> {
                } : afterChange;

        classSourcePanel.afterChange(this.afterChange);
        fieldSourcePanel.afterChange(this.afterChange);
    }

    public Object selected() {
        return selected;
    }

    public NodeSamplePanel samplePanel() {
        return samplePanel;
    }

    public NodePropertyDiscoveryPanel discoveryPanel() {
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

        /*
         * For fields, class-sample is still the owning root class.
         */
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
        if (selected instanceof GeneratedFieldModel) {
            fieldSourcePanel.useProperty(pid, label);
        }
    }

    private void buildUi() {
        cardPanel.add(classSourcePanel, "class");
        cardPanel.add(fieldSourcePanel, "field");
        cardPanel.add(new JLabel("Select the class or a field."), "empty");

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
