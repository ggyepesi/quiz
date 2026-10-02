package wikidata.explore.build;

import dataset.DomainStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wikidata.explore.extract.SnapshotDomain;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.generation.GraphDiscoveryResultStore;
import wikidata.explore.generation.GraphResults;
import wikidata.explore.generation.ProjectSave;
import wikidata.explore.model.BuildOperation;
import wikidata.explore.model.ClassKind;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GraphClassSource;
import work.QueryContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A saved build runs without a window, reusing what is current and saying why each step
 * runs (directive 22). These cases reach no network: every output a step would fetch is
 * either current, so it is reused, or the build stops before reaching it.
 */
class ProjectBuildTest {

    @TempDir Path root;

    /** Nothing saved: generation runs, and every later step runs because it does. */
    @Test void withNothingSavedEveryStepRunsAndSaysWhy() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = project();

        List<ProjectBuild.Step> plan = ProjectBuild.plan(model, storage);

        assertEquals(List.of(ProjectBuild.State.MISSING, ProjectBuild.State.STALE,
                        ProjectBuild.State.STALE, ProjectBuild.State.STALE),
                plan.stream().map(ProjectBuild.Step::state).toList());
        assertEquals("Run graph PositionGraph: STALE — because Generate project runs",
                plan.get(1).explain());
    }

    /** A saved snapshot and result made from the current model are reused; changing the
     *  model makes generation, and so everything after it, run. */
    @Test void currentOutputsAreReusedAndAModelChangeMakesThemStale() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = project();
        saveWithResult(model, storage, "Accepted", null);

        List<ProjectBuild.Step> current = ProjectBuild.plan(model, storage);
        assertEquals(List.of(ProjectBuild.State.CURRENT, ProjectBuild.State.CURRENT,
                        ProjectBuild.State.MISSING, ProjectBuild.State.STALE),
                current.stream().map(ProjectBuild.Step::state).toList(),
                "the saved result is current but was never applied");

        model.rootClass().generationDepth(model.rootClass().generationDepth() + 1);
        ProjectBuild.Step generate = ProjectBuild.plan(model, storage).getFirst();
        assertEquals(ProjectBuild.State.STALE, generate.state());
        assertEquals(List.of("the model changed since the snapshot was generated"),
                generate.because());
    }

    @Test void aChangedGraphConfigurationMakesItsResultStale() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = project();
        saveWithResult(model, storage, "Accepted", null);

        GeneratedClassModel graph = model.findClass("PositionGraph");
        graph.graphSource(new GraphClassSource(graph.graphSource().startNode(),
                List.of(node("P279"))));

        ProjectBuild.Step run = ProjectBuild.plan(model, storage).get(1);
        assertEquals(ProjectBuild.State.STALE, run.state());
        assertEquals(List.of("the configuration of PositionGraph changed"), run.because());
    }

    /** An undecided Review entry stops the build at Apply; Save is not reached, so the last
     *  complete output stays as it was, and the manifest says what happened. */
    @Test void aBuildStopsAtAwaitingDecisionWithoutReplacingTheSavedOutput() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = project();
        saveWithResult(model, storage, "Review", GraphDiscoveryResultStore.AWAIT_DECISION);
        String before = Files.readString(storage.snapshotFile(model.name()).toPath());

        ProjectBuild.Report report = ProjectBuild.run(model, storage, new QueryContext(), null);

        assertEquals(ProjectBuild.State.AWAITING_DECISION, report.outcome());
        assertEquals(List.of(ProjectBuild.Action.REUSED, ProjectBuild.Action.REUSED,
                        ProjectBuild.Action.STOPPED, ProjectBuild.Action.NOT_REACHED),
                report.steps().stream().map(ProjectBuild.Done::action).toList());
        assertTrue(report.steps().get(2).detail().contains("Position Q2 (Q2)"));
        assertEquals(before, Files.readString(storage.snapshotFile(model.name()).toPath()));
        assertTrue(Files.readString(report.manifest().toPath()).contains("AWAITING_DECISION"));
    }

    /** Decided, the same build applies the result and saves, naming every file it wrote. */
    @Test void aDecidedResultIsAppliedAndSaved() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = project();
        saveWithResult(model, storage, "Accepted", null);

        ProjectBuild.Report report = ProjectBuild.run(model, storage, new QueryContext(), null);

        assertEquals(ProjectBuild.State.CURRENT, report.outcome());
        assertEquals(List.of(ProjectBuild.Action.REUSED, ProjectBuild.Action.REUSED,
                        ProjectBuild.Action.RAN, ProjectBuild.Action.RAN),
                report.steps().stream().map(ProjectBuild.Done::action).toList());
        assertTrue(report.files().stream().anyMatch(line ->
                line.contains(storage.snapshotFile(model.name()).getPath())));
        assertEquals(List.of(ProjectBuild.State.CURRENT, ProjectBuild.State.CURRENT,
                        ProjectBuild.State.CURRENT, ProjectBuild.State.CURRENT),
                ProjectBuild.plan(model, storage).stream().map(ProjectBuild.Step::state).toList(),
                "a second build has nothing to do");
    }

    /** Saves generated Positions Q1 and Q2 and a PositionGraph result in which Q1 is
     *  accepted and Q2 carries {@code decision}, recorded from the current configuration. */
    private static void saveWithResult(GeneratedProjectModel model, DomainStorage storage,
                                       String decision, String disposition) throws Exception {
        GraphResults results = new GraphResults(model, storage);
        GeneratedClassModel graph = model.findClass("PositionGraph");
        results.record(graph, result(model,
                annotation("Q1", "Accepted", null), annotation("Q2", decision, disposition)));
        ProjectSave.plan(new ProjectSave.Input(model,
                new ProjectSave.Run(List.of(position("Q1"), position("Q2")), List.of(), null,
                        model.copy()), null, results.all()), storage).write();
    }

    private static GeneratedProjectModel project() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("Offices");
        model.rootClass().className("Position");
        GeneratedClassModel graph = model.getOrCreateClass("PositionGraph");
        graph.classKind(ClassKind.GRAPH);
        graph.graphSource(new GraphClassSource(
                new datasource.graph.GraphDiscoveryConfiguration.StartNode("Position", "",
                        datasource.graph.GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY),
                List.of(node("P31"))));
        String graphId = graph.declarationId();
        model.buildOperations(List.of(
                new BuildOperation(BuildOperation.Kind.GENERATE_PROJECT, ""),
                new BuildOperation(BuildOperation.Kind.RUN_GRAPH_CONSTRAINT, graphId),
                new BuildOperation(BuildOperation.Kind.APPLY_GRAPH_DECISIONS, graphId),
                new BuildOperation(BuildOperation.Kind.SAVE_PROJECT_RESULT, "")));
        return model;
    }

    private static datasource.graph.GraphDiscoveryConfiguration.NextNode node(String pid) {
        return new datasource.graph.GraphDiscoveryConfiguration.NextNode(
                new datasource.graph.GraphRelation("wikidata", pid),
                datasource.graph.GraphTraversalDirection.OUTGOING,
                datasource.graph.GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION,
                "Position", null);
    }

    private static WikidataDynamicObject position(String qid) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, "Position " + qid);
        value.type("Position");
        return value;
    }

    private static WikidataDynamicObject annotation(String qid, String decision,
                                                    String disposition) {
        WikidataDynamicObject annotation = new WikidataDynamicObject(qid, "Position " + qid);
        annotation.type("PositionGraph");
        annotation.put(GraphDiscoveryResultStore.GRAPH_DECISION, decision);
        if (disposition != null) {
            annotation.put(GraphDiscoveryResultStore.REVIEW_DISPOSITION, disposition);
        }
        annotation.put(GraphDiscoveryResultStore.ANNOTATED_INSTANCE, position(qid));
        return annotation;
    }

    private static GraphDiscoveryResultStore.Artifact result(GeneratedProjectModel model,
                                                             WikidataDynamicObject... records) {
        List<WikidataDynamicObject> annotations = List.of(records);
        List<WikidataDynamicObject> candidates = new ArrayList<>();
        for (WikidataDynamicObject value : annotations) {
            candidates.add((WikidataDynamicObject)
                    value.get(GraphDiscoveryResultStore.ANNOTATED_INSTANCE));
        }
        return new GraphDiscoveryResultStore.Artifact(model.name(), "PositionGraph", "Position",
                annotations, candidates, new SnapshotDomain(annotations,
                        GraphDiscoveryResultStore.fieldGraph("PositionGraph", "Position",
                                annotations)));
    }
}
