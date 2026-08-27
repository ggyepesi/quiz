package wikidata.explore.workbench;

import datasource.graph.GraphDiscoveryState;
import datasource.graph.GraphExpansionCoverage;
import datasource.graph.GraphExpansionPattern;
import process.ProcessOutcome;
import process.swing.workflow.PreparedProcessWorkflowAction;
import process.swing.workflow.ProcessWorkflowPlan;
import process.swing.workflow.ProcessWorkflowResults;
import wikidata.WikidataIds;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataObjectGraph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Shared workflow plug-in for reviewing and choosing graph-frontier nodes. */
final class GraphFrontierWorkflowAction
        implements PreparedProcessWorkflowAction<GraphDiscoveryState,
        GraphFrontierWorkflowAction.Decision> {

    record Decision(String patternId, String targetClass, String qid) { }

    private final GraphDiscoveryState state;
    private final Map<String, WikidataDynamicObject> byQid;
    private final Consumer<List<Decision>> apply;
    private final Runnable afterApply;
    private final int frontierCount;
    private final GraphCoverageCardDecorator decorator;

    GraphFrontierWorkflowAction(GraphDiscoveryState state,
                                Collection<WikidataDynamicObject> objects,
                                Consumer<List<Decision>> apply,
                                Runnable afterApply) {
        this.state = Objects.requireNonNull(state, "state");
        this.apply = Objects.requireNonNull(apply, "apply");
        this.afterApply = Objects.requireNonNull(afterApply, "afterApply");
        decorator = new GraphCoverageCardDecorator(state);
        byQid = new LinkedHashMap<>();
        // Frontier targets are commonly reference-only objects nested in a statement;
        // use the same reachable graph the snapshot writer saves, not served roots only.
        for (WikidataDynamicObject object : WikidataObjectGraph.reachable(objects)) {
            if (object != null && WikidataIds.isQid(object.qid())) {
                byQid.putIfAbsent(object.qid(), object);
            }
        }
        frontierCount = state.patterns().stream()
                .mapToInt(pattern -> frontier(pattern).size()).sum();
    }

    @Override public String id() { return "graph-frontier"; }

    /** Expanding independent frontier nodes is intentionally safe as one model edit. */
    @Override public boolean multipleResultSelection() { return true; }

    @Override public ProcessWorkflowPlan plan() {
        return new ProcessWorkflowPlan(
                "Graph frontier",
                "Review nodes encountered by the preceding generation and select which "
                        + "neighbourhoods to expand next.", List.of(),
                frontierCount > 0, "No partial nodes to expand");
    }

    @Override public ProcessOutcome<GraphDiscoveryState> preparedOutcome() {
        return ProcessOutcome.succeeded(state,
                frontierCount + " frontier node(s) ready for review");
    }

    @Override public ProcessWorkflowResults<Decision> results(
            ProcessOutcome<GraphDiscoveryState> outcome) {
        List<ProcessWorkflowResults.Tab<Decision>> tabs = new ArrayList<>();
        for (GraphExpansionPattern pattern : outcome.result().patterns()) {
            var expandedCards = views(expanded(pattern)).stream()
                    .map(view -> new ProcessWorkflowResults.Card<Decision>(
                            view, () -> null, false, () -> decorator.apply(view))).toList();
            tabs.add(new ProcessWorkflowResults.Tab<>(
                    pattern.targetNodeClass() + " expanded", expandedCards));
            var frontierCards = views(frontier(pattern)).stream()
                    .map(view -> new ProcessWorkflowResults.Card<>(view,
                            () -> new Decision(pattern.id(), pattern.targetNodeClass(), view.qid()),
                            false, () -> decorator.apply(view))).toList();
            tabs.add(new ProcessWorkflowResults.Tab<>(
                    pattern.targetNodeClass() + " frontier", frontierCards));
        }
        return new ProcessWorkflowResults<>(
                "Graph frontier", outcome.summary(), "Expand", tabs);
    }

    @Override public void apply(List<Decision> decisions) {
        apply.accept(decisions);
    }

    @Override public void afterApply() {
        afterApply.run();
    }

    private List<GraphExpansionCoverage> expanded(GraphExpansionPattern pattern) {
        return state.coverage(pattern, GraphExpansionCoverage.State.EXPANDED);
    }

    private List<GraphExpansionCoverage> frontier(GraphExpansionPattern pattern) {
        return state.frontier(pattern);
    }

    private List<WikidataDynamicObject> views(List<GraphExpansionCoverage> coverage) {
        return coverage.stream().map(item -> byQid.get(item.node().id()))
                .filter(Objects::nonNull).toList();
    }
}
