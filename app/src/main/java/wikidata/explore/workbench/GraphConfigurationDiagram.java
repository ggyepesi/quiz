package wikidata.explore.workbench;

import datasource.graph.GraphExpansionPattern;
import datasource.graph.GraphExpansionPolicy;
import wikidata.explore.generation.WikidataGraphDiscoveryState;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/** Configuration host around the shared graph-pattern renderer. */
final class GraphConfigurationDiagram extends JPanel {
    private final GeneratedProjectModel model;
    private final GraphPatternDiagram diagram = new GraphPatternDiagram();
    private Consumer<Object> onActivate = ignored -> {};
    private Object selection;
    private GraphExpansionPattern pattern;

    GraphConfigurationDiagram(GeneratedProjectModel model) {
        super(new BorderLayout());
        this.model = java.util.Objects.requireNonNull(model, "model");
        setBorder(BorderFactory.createTitledBorder("Configured graph expansion"));
        add(diagram, BorderLayout.CENTER);
        diagram.onActivate(this::activate);
        setVisible(false);
    }

    void onActivate(Consumer<Object> handler) {
        onActivate = handler == null ? ignored -> {} : handler;
    }

    void selection(Object value) {
        selection = value;
        refresh();
    }

    void refresh() {
        pattern = patternFor(model, selection);
        setVisible(pattern != null);
        diagram.pattern(pattern, GraphPatternDiagram.Details.configuration(pattern));
        revalidate();
        repaint();
    }

    private void activate(GraphPatternDiagram.Role role) {
        if (pattern == null) return;
        String className = switch (role) {
            case SOURCE -> pattern.sourceNodeClass();
            case STATEMENT -> pattern.statementClass();
            case SELECTED_TARGET, FRONTIER_TARGET -> pattern.targetNodeClass();
        };
        GeneratedClassModel target = model.findClass(className);
        if (target != null) onActivate.accept(target);
    }

    static GraphExpansionPattern patternFor(GeneratedProjectModel model, Object selected) {
        if (model == null) return null;
        GeneratedClassModel selectedClass = selected instanceof GeneratedClassModel clazz
                ? clazz : selected instanceof GeneratedFieldModel field
                ? model.declaringClass(field) : null;
        if (selectedClass != null) {
            GraphExpansionPattern selectedPattern = enabledPattern(model, selectedClass);
            if (selectedPattern != null) return selectedPattern;
        }
        for (GeneratedClassModel clazz : model.classes()) {
            GraphExpansionPattern candidate = enabledPattern(model, clazz);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private static GraphExpansionPattern enabledPattern(
            GeneratedProjectModel model, GeneratedClassModel clazz) {
        if (clazz == null || clazz.statementSource() == null
                || clazz.statementSource().graphExpansionPolicy()
                != GraphExpansionPolicy.CURATED) return null;
        return WikidataGraphDiscoveryState.structuralPattern(model, clazz.className());
    }
}
