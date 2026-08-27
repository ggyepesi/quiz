package wikidata.explore.workbench;

import datasource.graph.GraphDiscoveryState;
import datasource.graph.GraphExpansionCoverage;
import datasource.graph.GraphExpansionPattern;
import process.Process;
import process.ProcessContext;
import process.ProcessOutcome;
import process.ProcessPlan;
import process.swing.workflow.ProcessWorkflowAction;
import process.swing.workflow.ProcessWorkflowPlan;
import process.swing.workflow.ProcessWorkflowResults;
import wikidata.WikidataIds;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Shared workflow plug-in for reviewing and choosing graph-frontier nodes. */
final class GraphFrontierWorkflowAction
        implements ProcessWorkflowAction<GraphDiscoveryState,
        GraphFrontierWorkflowAction.Decision> {

    record Decision(String patternId, String targetClass, String qid) { }

    private final GraphDiscoveryState state;
    private final Map<String, WikidataDynamicObject> byQid;
    private final Consumer<List<Decision>> apply;
    private final int frontierCount;

    GraphFrontierWorkflowAction(GraphDiscoveryState state,
                                Collection<WikidataDynamicObject> objects,
                                Consumer<List<Decision>> apply) {
        this.state = Objects.requireNonNull(state, "state");
        this.apply = Objects.requireNonNull(apply, "apply");
        byQid = new LinkedHashMap<>();
        for (WikidataDynamicObject object : objects) {
            if (object != null && WikidataIds.isQid(object.qid())) {
                byQid.putIfAbsent(object.qid(), object);
            }
        }
        frontierCount = state.patterns().stream()
                .mapToInt(pattern -> state.frontier(pattern.id()).size()).sum();
    }

    @Override public String id() { return "graph-frontier"; }

    /** Expanding independent frontier nodes is intentionally safe as one model edit. */
    @Override public boolean multipleResultSelection() { return true; }

    @Override public ProcessWorkflowPlan plan() {
        return new ProcessWorkflowPlan(
                "Expand graph frontier",
                "These nodes were encountered through labelled edges but their reverse "
                        + "neighbourhood has not been enumerated. Execute to review them, "
                        + "then select the cards to expand.",
                state.patterns().stream().flatMap(pattern -> List.of(
                        new ProcessWorkflowPlan.Tab(pattern.targetNodeClass() + " expanded",
                                views(expanded(pattern))),
                        new ProcessWorkflowPlan.Tab(pattern.targetNodeClass() + " frontier",
                                views(state.frontier(pattern.id())))).stream()).toList(),
                frontierCount > 0, "No partial nodes to expand");
    }

    @Override public Process<GraphDiscoveryState> process() {
        return new Process<>() {
            @Override public ProcessPlan plan() {
                return new ProcessPlan("Prepare graph frontier",
                        "Resolve frontier nodes against the generated snapshot",
                        Map.of("nodes", String.valueOf(frontierCount)));
            }

            @Override public ProcessOutcome<GraphDiscoveryState> execute(ProcessContext context) {
                return ProcessOutcome.succeeded(state,
                        frontierCount + " frontier node(s) ready for review");
            }
        };
    }

    @Override public ProcessWorkflowResults<Decision> results(
            ProcessOutcome<GraphDiscoveryState> outcome) {
        List<ProcessWorkflowResults.Tab<Decision>> tabs = new ArrayList<>();
        for (GraphExpansionPattern pattern : outcome.result().patterns()) {
            var expandedCards = views(expanded(pattern)).stream()
                    .map(view -> new ProcessWorkflowResults.Card<Decision>(
                            view, () -> null, false)).toList();
            tabs.add(new ProcessWorkflowResults.Tab<>(
                    pattern.targetNodeClass() + " expanded", expandedCards));
            var frontierCards = views(outcome.result().frontier(pattern.id())).stream()
                    .map(view -> new ProcessWorkflowResults.Card<>(view,
                            () -> new Decision(pattern.id(), pattern.targetNodeClass(), view.qid()),
                            false)).toList();
            tabs.add(new ProcessWorkflowResults.Tab<>(
                    pattern.targetNodeClass() + " frontier", frontierCards));
        }
        return new ProcessWorkflowResults<>(
                "Graph frontier", outcome.summary(), "Expand", tabs);
    }

    @Override public void apply(List<Decision> decisions) {
        apply.accept(decisions);
    }

    private List<GraphExpansionCoverage> expanded(GraphExpansionPattern pattern) {
        return state.coverage().stream()
                .filter(item -> pattern.id().equals(item.patternId()))
                .filter(item -> item.state() == GraphExpansionCoverage.State.EXPANDED)
                .toList();
    }

    private List<WikidataDynamicObject> views(List<GraphExpansionCoverage> coverage) {
        return coverage.stream().map(item -> byQid.get(item.node().id()))
                .filter(Objects::nonNull).toList();
    }
}
