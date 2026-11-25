package domain.workbench.modelbuilder.ui;

import domain.build.GraphDiscoveryResultStore;
import domain.build.GraphResults;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.config.PopulationSelection;
import domain.config.Selection;
import domain.datasource.graph.GraphDiscoveryConfiguration;
import domain.datasource.graph.GraphRelation;
import domain.datasource.graph.GraphTraversalDirection;
import domain.datasource.graph.constraint.GraphEvidenceCondition;
import domain.datasource.graph.constraint.GraphNodeCondition;
import domain.datasource.graph.constraint.GraphNodeIsUnder;
import domain.datasource.graph.constraint.GraphPath;
import domain.datasource.graph.constraint.GraphRelationAbsent;
import domain.datasource.graph.constraint.GraphRelationExists;
import domain.datasource.graph.constraint.GraphRelationReaches;
import domain.datasource.graph.constraint.GraphRelationReachesUnder;
import domain.runtime.WikidataDynamicObject;
import domain.transform.DynamicViewable;
import graphview.GraphViewModel;
import objectview.Viewable;
import process.ProcessOutcome;
import process.ProcessPlan;
import process.ProcessWorkflowPipeline;
import process.QuerySubprocess;
import process.swing.workflow.ProcessWorkflowAction;
import process.swing.workflow.ProcessWorkflowPlan;
import process.swing.workflow.ProcessWorkflowResults;
import wikidata.explore.query.logical.ConfiguredGraphDiscoveryQuery;
import wikidata.ui.WikidataLinks;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;

/** The graph plan, query and decision cards shared by the interactive workbenches. */
public final class GraphRunWorkflow {
    private GraphRunWorkflow() { }

    @FunctionalInterface
    public interface AcceptResult {
        void accept(GraphDiscoveryResultStore.Artifact result) throws Exception;
    }

    public record ResultHandling(String verb, String closeVerb, String consequence,
            Callable<GraphDiscoveryResultStore.Artifact> previous, AcceptResult accept,
            Runnable afterAccept) { }

    public static boolean hasTraversal(GeneratedClassModel graphClass) {
        var graph = configurationOf(graphClass);
        return graph != null && !graph.name().isBlank() && !graph.nextNodes().isEmpty();
    }

    public static ProcessWorkflowAction<ConfiguredGraphDiscoveryQuery.Result,
            GraphDiscoveryResultStore.Artifact> action(GeneratedProjectModel model,
            GeneratedClassModel selected, Collection<? extends Viewable> loadedInstances,
            File annotationFile, ResultHandling handling) {
        GeneratedProjectModel snapshot = model.copy();
        GeneratedClassModel graphClass = snapshot.findClass(selected.className());
        var instances = List.copyOf(loadedInstances);
        var query = new ConfiguredGraphDiscoveryQuery(snapshot, graphClass, instances);
        var pipeline = new ProcessWorkflowPipeline(List.of(
                new ProcessWorkflowPipeline.Phase("discover-graph", "Discover and classify graph nodes",
                        "Follow the configured edge, acquire evidence and classify each reached entity.",
                        graphDetails(snapshot, graphClass))));
        var summary = graphSummary(snapshot, graphClass, instances, annotationFile);
        return new ProcessWorkflowAction<>() {
            private GraphDiscoveryResultStore.Artifact previous;

            @Override public String id() { return "discover-graph"; }
            @Override public String subject() { return graphClass.declarationId(); }
            @Override public ProcessWorkflowPipeline pipeline() { return pipeline; }
            @Override public java.util.function.Function<Object, String> valueLinker() {
                return WikidataLinks.valueLinker();
            }
            @Override public boolean multipleResultSelection() { return true; }
            @Override public ProcessWorkflowPlan plan() {
                return new ProcessWorkflowPlan("Run " + graphClass.className(),
                        "Inspect the configured traversal and evidence tests, then explicitly start discovery.",
                        List.of(ProcessWorkflowPlan.Tab.component(
                                        "Graph", () -> graphPlanView(snapshot, graphClass, instances), true),
                                new ProcessWorkflowPlan.Tab("Scope", List.of(summary)))).withoutPipelineTab();
            }
            @Override public process.Process<ConfiguredGraphDiscoveryQuery.Result> process() {
                return new process.Process<>() {
                    @Override public ProcessPlan plan() {
                        return new ProcessPlan(query.purpose(), query.description(), query.parameters());
                    }
                    @Override public ProcessOutcome<ConfiguredGraphDiscoveryQuery.Result>
                            execute(process.ProcessContext context) throws Exception {
                        pipeline.start("discover-graph", query.purpose());
                        previous = handling.previous().call();
                        var outcome = context.run(new QuerySubprocess<>(query));
                        pipeline.finish(outcome.status(), outcome.summary());
                        return outcome;
                    }
                };
            }
            @Override public ProcessWorkflowResults<GraphDiscoveryResultStore.Artifact> results(
                    ProcessOutcome<ConfiguredGraphDiscoveryQuery.Result> outcome) {
                var artifact = GraphDiscoveryResultStore.artifact(snapshot.name(), graphClass.className(),
                        outcome.result());
                GraphResults.prepare(snapshot, graphClass, previous, artifact);
                return resultViews(outcome.result(), artifact, handling.verb(), handling.closeVerb(),
                        handling.consequence());
            }
            @Override public void apply(List<GraphDiscoveryResultStore.Artifact> decisions) throws Exception {
                handling.accept().accept(decisions.getFirst());
            }
            @Override public void afterApply() { handling.afterAccept().run(); }
        };
    }

    static ProcessWorkflowResults<GraphDiscoveryResultStore.Artifact> resultViews(
            ConfiguredGraphDiscoveryQuery.Result result, GraphDiscoveryResultStore.Artifact artifact,
            String verb, String closeVerb, String consequence) {
        var graph = result.graph();
        var last = graph.nodes().isEmpty() ? null : graph.nodes().getLast();
        int reached = last == null ? 0 : last.reached().size();
        int accepted = last == null ? 0 : last.accepted().size();
        int review = last == null ? 0 : last.review().size();
        int rejected = last == null ? 0 : last.rejected().size();
        int unavailable = last == null ? 0 : last.unavailable().size();
        int incomplete = last == null ? 0 : last.incomplete().size();
        String labels = result.labelledEntities() < graph.start().size() + reached
                ? "; labels loaded for the first " + result.labelledEntities() : "";
        String summary = reached + " reached: " + accepted + " accepted, " + review
                + " review, " + rejected + " rejected"
                + (unavailable + incomplete == 0 ? "" : "; " + unavailable
                        + " unavailable, " + incomplete + " incomplete") + labels
                + ". " + consequence;
        return new ProcessWorkflowResults<>("Run graph — results", summary, verb,
                List.of(artifactTab("All", artifact, null),
                        artifactTab("Accepted", artifact, "Accepted"),
                        artifactTab("Review", artifact, "Review"),
                        artifactTab("Rejected", artifact, "Rejected")),
                () -> artifact, closeVerb);
    }

    static ProcessWorkflowResults.Tab<GraphDiscoveryResultStore.Artifact> artifactTab(
            String title, GraphDiscoveryResultStore.Artifact artifact, String decision) {
        List<ProcessWorkflowResults.Card<GraphDiscoveryResultStore.Artifact>> cards =
                artifact.instances().stream()
                        .filter(value -> decision == null
                                || decisionValue(value).contains(decision))
                        .map(value -> new ProcessWorkflowResults.Card<GraphDiscoveryResultStore.Artifact>(
                                GraphResultPresentation.candidate(value), () -> null, false,
                                () -> GraphResultPresentation.decoration(value))).toList();
        return new ProcessWorkflowResults.Tab<>(title + " — " + cards.size() + " total", cards,
                GraphResultPresentation.shapeSample(artifact),
                GraphResultPresentation.decisionActions(artifact));
    }

    private static List<String> decisionValue(WikidataDynamicObject value) {
        return GraphDiscoveryResultStore.originalDecisions(value);
    }

    static DynamicViewable graphSummary(GeneratedProjectModel snapshot,
            GeneratedClassModel graphClass,
            Collection<? extends Viewable> instances, File file) {
        GraphDiscoveryConfiguration graph = configurationOf(graphClass);
        // The summary's fields depend on the configured nodes: they are collected,
        // declared as the record type's fields, and only then written (#363).
        java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("Adjacency facts",
                "Load saved answers from the persistent graph cache; download only missing answers");
        fields.put("Classification",
                "Repeat locally from the loaded graph facts");
        fields.put("Labels",
                "Fetch again; labels are not stored in the graph cache");
        fields.put("Results",
                "Review the reached candidates. Save the kept annotations with the project.");
        fields.put("Results file",
                file.getPath());
        fields.put("Previous decisions", "Carry forward manual decisions for reached entities.");
        fields.put("Annotation set", graph.name());
        fields.put("Start class", startClassName(snapshot, graph.startNode()));
        fields.put("Start input", graph.startNode().populationSelection().isBlank()
                ? "All loaded " + graph.startNode().qidSourceClass() + " instances"
                : "Saved population " + graph.startNode().populationSelection());
        fields.put("Start QIDs", startQidCount(snapshot, graph.startNode(), instances));
        for (int index = 0; index < graph.nextNodes().size(); index++) {
            GraphDiscoveryConfiguration.NextNode next = graph.nextNodes().get(index);
            String prefix = graph.nextNodes().size() == 1 ? "" : "Node " + (index + 1) + " ";
            fields.put(prefix + "Edge", next.property().relationId() + " "
                    + directionLabel(next.directionFromPrevious()));
            fields.put(prefix + "Reached entities", next.use()
                    == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION
                    ? "Members of " + next.populationClass() : "Intermediate only");
            fields.put(prefix + "Admit and expand", next.admissionPopulationSelection().isBlank()
                    ? "Any reached entity"
                    : "Members of " + next.admissionPopulationSelection());
            if (!next.admissionPopulationSelection().isBlank()) {
                fields.put(prefix + "Outside population",
                        "Rejected boundary entities, retained with the traversal witness, and not expanded");
            }
            GraphEvidenceCondition evidence = next.evidenceCondition();
            fields.put(prefix + "Evidence relations",
                    evidence == null ? 0 : evidence.evidencePaths().size());
            fields.put(prefix + "Evidence tests",
                    evidence == null ? 0 : evidence.tests().size());
        }
        objectview.field.RecordTypes.declare("Graph discovery", fields.keySet().stream()
                .map(objectview.field.RecordTypes::value).toList());
        DynamicViewable summary = new DynamicViewable("graph-plan", "Configured graph");
        summary.type("Graph discovery");
        fields.forEach(summary::put);
        return summary;
    }

    private static JComponent graphPlanView(GeneratedProjectModel snapshot,
            GeneratedClassModel graphClass,
            java.util.Collection<? extends Viewable> instances) {
        GraphDiscoveryPlanDiagram diagram = new GraphDiscoveryPlanDiagram(
                graphPlanModel(snapshot, graphClass, instances), reviewPolicy(graphClass));
        return new JScrollPane(diagram);
    }

    /** One graph-model projection of the configuration used by both tests and the plan view. */
    static GraphViewModel graphPlanModel(
            GeneratedProjectModel snapshot, GeneratedClassModel graphClass) {
        return graphPlanModel(snapshot, graphClass, List.of());
    }

    static GraphViewModel graphPlanModel(GeneratedProjectModel snapshot,
            GeneratedClassModel graphClass,
            java.util.Collection<? extends Viewable> instances) {
        GraphDiscoveryConfiguration graph = configurationOf(graphClass);
        if (graph == null) return new GraphViewModel(List.of(), List.of());
        List<GraphViewModel.Node> nodes = new ArrayList<>();
        List<GraphViewModel.Edge> edges = new ArrayList<>();
        nodes.add(new GraphViewModel.Node("start", startClassName(snapshot, graph.startNode()), null,
                0, GraphViewModel.State.EXPANDED,
                java.util.Map.of(
                        "QIDs", Integer.toString(startQidCount(
                                snapshot, graph.startNode(), instances)),
                        "Population", graph.startNode().populationSelection().isBlank()
                                ? "All loaded class instances"
                                : graph.startNode().populationSelection(),
                        "Use", nodeUse(graph.startNode().use(),
                                startClassName(snapshot, graph.startNode()))),
                graph.startNode()));
        String previous = "start";
        int index = 0;
        for (GraphDiscoveryConfiguration.NextNode next : graph.nextNodes()) {
            int nextLevel = index * 3 + 1;
            String nextId = "next-" + index;
            String nextLabel = next.use() == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION
                    ? next.populationClass() : "Intermediate node";
            java.util.Map<String, String> metadata = new java.util.LinkedHashMap<>();
            metadata.put("Use", nodeUse(next.use(), next.populationClass()));
            metadata.put("Admit and expand", next.admissionPopulationSelection().isBlank()
                    ? "Any reached entity"
                    : "Members of " + next.admissionPopulationSelection());
            nodes.add(new GraphViewModel.Node(nextId, nextLabel, null, nextLevel,
                    GraphViewModel.State.DEFAULT, metadata, next));
            edges.add(new GraphViewModel.Edge("traversal-" + index, previous, nextId,
                    edgeLabel(next.property(), next.directionFromPrevious()), true));

            GraphEvidenceCondition evidence = next.evidenceCondition();
            if (evidence != null) {
                for (int pathIndex = 0; pathIndex < evidence.evidencePaths().size(); pathIndex++) {
                    GraphPath path = evidence.evidencePaths().get(pathIndex);
                    String evidenceId = "evidence-" + index + "-" + pathIndex;
                    nodes.add(new GraphViewModel.Node(evidenceId, "Evidence entity", null,
                            nextLevel + 1, GraphViewModel.State.DEFAULT,
                            java.util.Map.of("Condition", evidence.name()), path));
                    edges.add(new GraphViewModel.Edge("evidence-edge-" + index + "-" + pathIndex,
                            nextId, evidenceId, edgeLabel(path.relation(), path.direction()), true));
                    for (int testIndex = 0; testIndex < evidence.tests().size(); testIndex++) {
                        GraphNodeCondition test = evidence.tests().get(testIndex);
                        String testId = "test-" + index + "-" + testIndex;
                        if (nodes.stream().noneMatch(node -> node.id().equals(testId))) {
                            nodes.add(testNode(testId, nextLevel + 2, test));
                        }
                        edges.add(new GraphViewModel.Edge("test-edge-" + index + "-"
                                + pathIndex + "-" + testIndex, evidenceId, testId,
                                conditionEdgeLabel(test), true));
                    }
                }
            }
            previous = nextId;
            index++;
        }
        return new GraphViewModel(nodes, edges);
    }

    private static GraphViewModel.Node testNode(
            String id, int level, GraphNodeCondition condition) {
        String label;
        String result;
        if (condition instanceof GraphRelationExists) {
            label = "Has a value";
            result = "Accept when present";
        } else if (condition instanceof GraphRelationAbsent) {
            label = "Has no value";
            result = "Accept when absent";
        } else if (condition instanceof GraphRelationReaches reaches) {
            label = reaches.entity().id();
            result = "Accept when reached";
        } else if (condition instanceof GraphRelationReachesUnder reaches) {
            label = reaches.entity().id();
            result = "Accept when reached directly or below it";
        } else if (condition instanceof GraphNodeIsUnder under) {
            label = under.entity().id();
            result = "Accept when this node is the entity or below it";
        } else {
            label = "Evidence test";
            result = "Configured condition";
        }
        return new GraphViewModel.Node(id, label, null, level,
                GraphViewModel.State.FRONTIER, java.util.Map.of("Result", result), condition);
    }

    private static String conditionEdgeLabel(GraphNodeCondition condition) {
        if (condition instanceof GraphRelationExists exists) {
            return edgeLabel(exists.relation(), exists.direction());
        }
        if (condition instanceof GraphRelationAbsent absent) {
            return edgeLabel(absent.relation(), absent.direction());
        }
        if (condition instanceof GraphRelationReaches reaches) {
            return edgeLabel(reaches.relation(), reaches.direction());
        }
        if (condition instanceof GraphRelationReachesUnder reaches) {
            return edgeLabel(reaches.relation(), reaches.direction());
        }
        if (condition instanceof GraphNodeIsUnder under) {
            return "is or " + edgeLabel(under.via(), GraphTraversalDirection.OUTGOING);
        }
        return "test";
    }

    private static String edgeLabel(
            GraphRelation relation, GraphTraversalDirection direction) {
        return relation.relationId() + " " + directionLabel(direction);
    }

    private static String nodeUse(
            GraphDiscoveryConfiguration.NodeUse use, String populationClass) {
        return use == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION
                ? "Add to " + populationClass : "Intermediate only";
    }

    private static String reviewPolicy(GeneratedClassModel graphClass) {
        GraphDiscoveryConfiguration graph = configurationOf(graphClass);
        if (graph == null) return "";
        return graph.nextNodes().stream().map(GraphDiscoveryConfiguration.NextNode::evidenceCondition)
                .filter(java.util.Objects::nonNull)
                .map(condition -> switch (condition.reviewDisposition()) {
                    case AWAIT_DECISION -> "Undecidable nodes continue, are reported in Review,"
                            + " and must be decided before the result is applied.";
                    case INCLUDE_AND_REPORT -> "Undecidable nodes continue and are reported in Review.";
                    case EXCLUDE_AND_REPORT -> "Undecidable nodes stop and are reported in Review.";
                })
                .distinct().collect(java.util.stream.Collectors.joining(" "));
    }

    private static List<String> graphDetails(
            GeneratedProjectModel snapshot, GeneratedClassModel graphClass) {
        GraphDiscoveryConfiguration graph = configurationOf(graphClass);
        if (graph == null || graph.nextNodes().isEmpty()) return List.of();
        StringBuilder path = new StringBuilder(startInputLabel(snapshot, graph.startNode()));
        for (GraphDiscoveryConfiguration.NextNode next : graph.nextNodes()) {
            path.append(" → ").append(next.property().relationId()).append(' ')
                    .append(directionLabel(next.directionFromPrevious())).append(" → ")
                    .append(next.populationClass().isBlank()
                            ? "intermediate node" : next.populationClass());
        }
        return List.of(path.toString());
    }

    private static int startQidCount(GeneratedProjectModel model,
            GraphDiscoveryConfiguration.StartNode start,
            java.util.Collection<? extends Viewable> instances) {
        Selection selection = model.findSelection(start.populationSelection());
        if (selection instanceof PopulationSelection population) {
            return population.instanceQids().size();
        }
        return ConfiguredGraphDiscoveryQuery.loadedQids(instances, start.qidSourceClass()).size();
    }

    private static String startClassName(GeneratedProjectModel model,
            GraphDiscoveryConfiguration.StartNode start) {
        Selection selection = model.findSelection(start.populationSelection());
        return selection instanceof PopulationSelection population
                ? population.className() : start.qidSourceClass();
    }

    static String startInputLabel(GeneratedProjectModel model,
            GraphDiscoveryConfiguration.StartNode start) {
        return start.populationSelection().isBlank()
                ? "All loaded " + start.qidSourceClass() + " instances"
                : "Population " + start.populationSelection() + " ("
                        + startClassName(model, start) + ")";
    }


    static GraphDiscoveryConfiguration configurationOf(GeneratedClassModel graphClass) {
        return graphClass == null || graphClass.graphSource() == null ? null
                : graphClass.graphSource().configurationFor(graphClass.className());
    }


    static String directionLabel(GraphTraversalDirection direction) {
        return direction == GraphTraversalDirection.INCOMING ? "in" : "out";
    }

}
