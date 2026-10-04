package wikidata.explore.workbench;

import objectview.Viewable;
import process.swing.workflow.ProcessWorkflowResults;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.generation.GraphDiscoveryResultStore;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.util.IdentityHashMap;
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
final class GraphResultPresentation {
    private GraphResultPresentation() { }

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
        Map<Viewable, WikidataDynamicObject> annotations = annotationsByCandidate(artifact);
        return List.of(
                decisionAction("Accept selection", "Accepted", annotations),
                decisionAction("Reject selection", "Rejected", annotations),
                decisionAction("Clear manual decision", null, annotations));
    }

    private static ProcessWorkflowResults.SelectionAction decisionAction(
            String label, String decision,
            Map<Viewable, WikidataDynamicObject> annotations) {
        return new ProcessWorkflowResults.SelectionAction(label, values -> values.stream()
                .map(annotations::get).filter(java.util.Objects::nonNull)
                .forEach(value -> GraphDiscoveryResultStore.manualDecision(value, decision)));
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
