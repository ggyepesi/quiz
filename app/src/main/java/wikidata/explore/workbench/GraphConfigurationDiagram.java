package wikidata.explore.workbench;

import datasource.graph.GraphExpansionPattern;
import datasource.graph.GraphExpansionPlan;
import datasource.graph.GraphTraversalStep;
import wikidata.explore.generation.WikidataGraphExpansionPlan;
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
        diagram.onStepActivate(this::activateStep);
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
        GraphExpansionPlan plan = WikidataGraphExpansionPlan.compile(model);
        pattern = patternFor(plan, model, selection);
        setVisible(!plan.isEmpty());
        diagram.pattern(pattern, GraphPatternDiagram.Details.configuration(pattern));
        diagram.traversalSteps(plan.traversalSteps());
        revalidate();
        repaint();
    }

    private void activateStep(GraphTraversalStep step) {
        GeneratedClassModel owner = model.findClass(step.sourceNodeClass());
        if (owner == null) return;
        owner.fields().stream().filter(field -> step.sourceField().equals(field.name()))
                .findFirst().ifPresent(onActivate);
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
        return patternFor(WikidataGraphExpansionPlan.compile(model), model, selected);
    }

    private static GraphExpansionPattern patternFor(
            GraphExpansionPlan plan, GeneratedProjectModel model, Object selected) {
        GeneratedClassModel selectedClass = selected instanceof GeneratedClassModel clazz
                ? clazz : selected instanceof GeneratedFieldModel field
                ? model.declaringClass(field) : null;
        if (selectedClass != null) {
            for (GraphExpansionPattern candidate : plan.patterns()) {
                if (selectedClass.className().equals(candidate.statementClass())) return candidate;
            }
        }
        return plan.patterns().isEmpty() ? null : plan.patterns().getFirst();
    }
}
