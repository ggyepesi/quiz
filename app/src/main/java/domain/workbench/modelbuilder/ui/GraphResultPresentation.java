package domain.workbench.modelbuilder.ui;

import objectview.Viewable;
import process.swing.workflow.ProcessWorkflowResults;
import domain.runtime.WikidataDynamicObject;
import domain.build.GraphDiscoveryResultStore;
import domain.workbench.execution.ui.QueryObjectResultPanel;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Presents a graph annotation as the output-class instance it classifies.
 *
 * <p>The annotation remains the persisted, graph-specific state: two graphs may decide
 * differently about the same entity. The card is the exact candidate referenced by that
 * annotation, with only its decision, manual override and per-candidate reason added as a
 * decoration. Traversal configuration belongs to the graph editor, not to every card.
 */
public final class GraphResultPresentation {
    private GraphResultPresentation() { }

    /** The graph's original decision tabs, with edits recorded separately on the annotations. */
    public static QueryObjectResultPanel.GroupedSection review(
            GraphDiscoveryResultStore.Artifact result,
            List<QueryObjectResultPanel.GroupAction> actions, Runnable changed) {
        Map<String, List<Viewable>> partitions = new LinkedHashMap<>();
        for (String decision : List.of("Accepted", "Review", "Rejected")) {
            partitions.put(decision, result.instances().stream()
                    .filter(value -> GraphDiscoveryResultStore.originalDecisions(value).contains(decision))
                    .map(GraphResultPresentation::candidate).toList());
        }
        return QueryObjectResultPanel.GroupedSection.presented(candidates(result.instances()),
                partitions, actions, decisionActions(result, changed), shapeSample(result), decorator(result));
    }

    static Viewable candidate(WikidataDynamicObject annotation) {
        Object value = annotation == null ? null
                : annotation.get(GraphDiscoveryResultStore.ANNOTATED_INSTANCE);
        return value instanceof Viewable view ? view : annotation;
    }

    static List<Viewable> candidates(
            List<? extends WikidataDynamicObject> annotations) {
        if (annotations == null) return List.of();
        return annotations.stream().map(GraphResultPresentation::candidate).toList();
    }

    /** The output-class schema without its hidden reverse reference to the annotation. */
    static Viewable shapeSample(GraphDiscoveryResultStore.Artifact artifact) {
        Viewable sample = artifact == null ? null
                : artifact.model().representativeSample(artifact.outputClass());
        if (sample instanceof WikidataDynamicObject dynamic) {
            dynamic.remove(GraphDiscoveryResultStore.GRAPH_ANNOTATION);
        }
        return sample;
    }

    static Function<Viewable, JComponent> decorator(
            GraphDiscoveryResultStore.Artifact artifact) {
        Map<Viewable, WikidataDynamicObject> annotations = annotationsByCandidate(artifact);
        return value -> decoration(annotations.get(value));
    }

    static List<ProcessWorkflowResults.SelectionAction> decisionActions(
            GraphDiscoveryResultStore.Artifact artifact) {
        return decisionActions(artifact, () -> { });
    }

    private static List<ProcessWorkflowResults.SelectionAction> decisionActions(
            GraphDiscoveryResultStore.Artifact artifact, Runnable changed) {
        Map<Viewable, WikidataDynamicObject> annotations = annotationsByCandidate(artifact);
        return List.of(
                decisionAction("Accept selection", "Accepted", artifact, annotations, changed),
                decisionAction("Reject selection", "Rejected", artifact, annotations, changed),
                decisionAction("Clear manual decision", null, artifact, annotations, changed));
    }

    private static ProcessWorkflowResults.SelectionAction decisionAction(
            String label, String decision, GraphDiscoveryResultStore.Artifact artifact,
            Map<Viewable, WikidataDynamicObject> annotations, Runnable changed) {
        return new ProcessWorkflowResults.SelectionAction(label, values -> {
            var selected = values.stream().map(annotations::get).filter(java.util.Objects::nonNull).toList();
            if (GraphDiscoveryResultStore.review(artifact, selected, decision)) changed.run();
        });
    }

    private static Map<Viewable, WikidataDynamicObject> annotationsByCandidate(
            GraphDiscoveryResultStore.Artifact artifact) {
        Map<Viewable, WikidataDynamicObject> result = new IdentityHashMap<>();
        if (artifact != null) artifact.instances().forEach(annotation ->
                result.put(candidate(annotation), annotation));
        return result;
    }

    static JComponent decoration(WikidataDynamicObject annotation) {
        if (annotation == null) return null;
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        Object original = annotation.get(GraphDiscoveryResultStore.GRAPH_DECISION);
        if (original != null) {
            JLabel decision = new JLabel("● " + original);
            decision.setForeground(decisionColor(String.valueOf(original)));
            panel.add(decision);
        }
        Object manual = annotation.get(GraphDiscoveryResultStore.MANUAL_DECISION);
        if (manual != null) {
            JLabel override = new JLabel("Manual decision: " + manual);
            override.setForeground(decisionColor(String.valueOf(manual)));
            panel.add(override);
        }
        Object reason = annotation.get("Reason");
        if (reason != null && !String.valueOf(reason).isBlank()) {
            panel.add(new JLabel("Reason: " + reason));
        }
        return panel.getComponentCount() == 0 ? null : panel;
    }

    private static Color decisionColor(String decision) {
        return switch (decision) {
            case "Accepted" -> new Color(35, 125, 55);
            case "Rejected" -> new Color(175, 45, 40);
            default -> new Color(150, 105, 25);
        };
    }
}
