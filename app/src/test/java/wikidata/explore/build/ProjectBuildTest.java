package wikidata.explore.build;

import dataset.DomainStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wikidata.explore.extract.SnapshotDomain;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.generation.GenerationStepOutputStore;
import wikidata.explore.generation.GraphDiscoveryResultStore;
import wikidata.explore.generation.GraphResults;
import wikidata.explore.generation.ProjectSave;
import wikidata.explore.model.BuildOperation;
import wikidata.explore.model.ClassKind;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GraphClassSource;
import wikidata.explore.transform.SelfReferenceLedger;
import work.QueryContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A saved build runs without a window, reusing what is current and saying why each step
 * runs (directive 23). These cases reach no network: every output a step would fetch is
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
        assertEquals(List.of("the model changed since this step generated"),
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

    /**
     * #430: Historical Positions' final replacement apply said 63 accepted identities
     * were still ungenerated, then Publish and Save ran and the manifest said CURRENT.
     * A final additive apply is not a complete output until all of its accepted identities
     * are members; only a later Generate is allowed to defer that obligation.
     */
    @Test void aFinalAdditiveApplyWithMissingIdentitiesStopsBeforePublishOrSave()
            throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = additiveProject(false);
        GeneratedClassModel graph = model.findClass("PositionGraph");
        model.buildOperations(List.of(
                new BuildOperation(BuildOperation.Kind.APPLY_GRAPH_DECISIONS,
                        graph.declarationId()),
                new BuildOperation(BuildOperation.Kind.SAVE_PROJECT_RESULT, "")));
        saveAppliedAdditiveResult(model, storage);
        String before = Files.readString(storage.snapshotFile(model.name()).toPath());

        List<ProjectBuild.Step> plan = ProjectBuild.plan(model, storage);
        assertEquals(ProjectBuild.State.STALE, plan.getFirst().state());
        assertEquals(List.of("1 accepted PositionGraph identity is not a member of Position"),
                plan.getFirst().because());

        ProjectBuild.Report report = ProjectBuild.run(model, storage, new QueryContext(), null);

        assertEquals(ProjectBuild.State.INCOMPLETE, report.outcome());
        assertEquals(List.of(ProjectBuild.Action.STOPPED, ProjectBuild.Action.NOT_REACHED),
                report.steps().stream().map(ProjectBuild.Done::action).toList());
        assertTrue(report.steps().getFirst().detail().contains(
                "no later Generate project step will acquire the 1 missing accepted identity"),
                report.steps().getFirst().detail());
        assertEquals(before, Files.readString(storage.snapshotFile(model.name()).toPath()),
                "the last complete snapshot is not replaced");
        assertTrue(Files.readString(report.manifest().toPath()).contains("INCOMPLETE"));
    }

    /**
     * #423: a reused Generate hands on what IT produced. The project snapshot is what Save
     * project wrote after later steps reshaped that output: here it holds only Q1, while
     * the Generate step produced Q1, Q2 and Q3. Reading the snapshot is how Historical
     * Positions' replacement chain started from the previous build's expansion.
     */
    @Test void aReusedGenerateSuppliesItsOwnOutputNotTheReshapedSnapshot() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = project();
        saveWithResult(model, storage, "Accepted", null);
        ProjectSave.plan(new ProjectSave.Input(model,
                new ProjectSave.Run(List.of(position("Q1")), List.of(), null, model.copy()),
                null, new GraphResults(model, storage).all()), storage).write();
        saveGenerateOutputs(model, storage,
                List.of(position("Q1"), position("Q2"), position("Q3")));

        ProjectBuild.Report report = ProjectBuild.run(model, storage, new QueryContext(), null);

        assertEquals(ProjectBuild.State.CURRENT, report.outcome());
        assertEquals(ProjectBuild.Action.REUSED, report.steps().getFirst().action());
        assertTrue(report.steps().get(2).detail().contains(
                        "kept 2 accepted Position instance(s) from 3 existing instance(s)"),
                report.steps().get(2).detail());
    }

    /**
     * The Historical Positions shape (#423, #424): an additive graph runs between two
     * Generates. Its saved result accepted Q3, which was never generated. The first
     * Generate must not acquire Q3: the graph runs again after it, and Q3 in its input
     * would keep the rerun from reaching it, so each build would depend on the last. The
     * Generate after the graph's apply is the one that owes Q3.
     */
    @Test void aGenerateBeforeAGraphRunLeavesThatGraphsSavedIdentitiesToTheGenerateAfterIt()
            throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = additiveProject(true);
        saveAppliedAdditiveResult(model, storage);

        List<ProjectBuild.Step> plan = ProjectBuild.plan(model, storage);

        assertEquals(List.of(ProjectBuild.State.CURRENT, ProjectBuild.State.CURRENT,
                        ProjectBuild.State.CURRENT, ProjectBuild.State.STALE,
                        ProjectBuild.State.STALE),
                plan.stream().map(ProjectBuild.Step::state).toList());
        assertEquals(List.of("it now acquires 2 accepted graph identities, "
                        + "its output acquired 0"), plan.get(3).because());
        GraphResults saved = new GraphResults(model, storage);
        saved.restore(List.of(), ignored -> { });
        assertTrue(ProjectBuild.acquisitionsOf(model, saved,
                        model.buildOperations().getFirst()).isEmpty(),
                "the first Generate acquires nothing from a graph that runs after it");
        assertTrue(ProjectBuild.laterGenerateAcquires(model, saved,
                        model.buildOperations().get(2), model.findClass("PositionGraph")),
                "the applied result is explicitly owed by the later Generate");
    }

    /** The other side: a saved result the build does not run again is current, so its
     *  ungenerated identities are owed by the build's Generate. */
    @Test void aSavedResultTheBuildDoesNotRerunIsAcquiredByItsGenerate() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = additiveProject(false);
        saveAppliedAdditiveResult(model, storage);

        ProjectBuild.Step generate = ProjectBuild.plan(model, storage).getFirst();

        assertEquals(ProjectBuild.State.STALE, generate.state());
        assertEquals(List.of("it now acquires 2 accepted graph identities, "
                        + "its output acquired 0"), generate.because());
    }

    /** Offices whose PositionGraph ADDS to Position; with {@code graphInBuild} the build is
     *  Generate, Run graph, Apply, Generate, Save, otherwise Generate, Save. */
    private static GeneratedProjectModel additiveProject(boolean graphInBuild) {
        GeneratedProjectModel model = project();
        GeneratedClassModel graph = model.findClass("PositionGraph");
        graph.graphSource(new GraphClassSource(graph.graphSource().startNode(), List.of(
                new datasource.graph.GraphDiscoveryConfiguration.NextNode(
                        new datasource.graph.GraphRelation("wikidata", "P1366"),
                        datasource.graph.GraphTraversalDirection.OUTGOING,
                        datasource.graph.GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION,
                        "Position", null, List.of(),
                        datasource.graph.GraphDiscoveryConfiguration.PopulationOperation.ADD,
                        false))));
        String graphId = graph.declarationId();
        model.buildOperations(graphInBuild
                ? List.of(new BuildOperation(BuildOperation.Kind.GENERATE_PROJECT, ""),
                        new BuildOperation(BuildOperation.Kind.RUN_GRAPH_CONSTRAINT, graphId),
                        new BuildOperation(BuildOperation.Kind.APPLY_GRAPH_DECISIONS, graphId),
                        new BuildOperation(BuildOperation.Kind.GENERATE_PROJECT, ""),
                        new BuildOperation(BuildOperation.Kind.SAVE_PROJECT_RESULT, ""))
                : List.of(new BuildOperation(BuildOperation.Kind.GENERATE_PROJECT, ""),
                        new BuildOperation(BuildOperation.Kind.SAVE_PROJECT_RESULT, "")));
        return model;
    }

    /** Saves generated Position Q1 and an applied, additive PositionGraph result that
     *  accepted Q1 and the never-generated Q3. */
    private static void saveAppliedAdditiveResult(GeneratedProjectModel model,
                                                  DomainStorage storage) throws Exception {
        GraphResults results = new GraphResults(model, storage);
        GeneratedClassModel graph = model.findClass("PositionGraph");
        GraphDiscoveryResultStore.Artifact plain = result(model,
                annotation("Q1", "Accepted", null), annotation("Q3", "Accepted", null));
        results.record(graph, GraphDiscoveryResultStore.applied(
                new GraphDiscoveryResultStore.Artifact(plain.projectName(), plain.type(),
                        plain.outputClass(),
                        datasource.graph.GraphDiscoveryConfiguration.PopulationOperation.ADD,
                        plain.instances(), plain.candidates(), plain.model())));
        ProjectSave.plan(new ProjectSave.Input(model,
                new ProjectSave.Run(List.of(position("Q1")), List.of(), null, model.copy()),
                null, results.all()), storage).write();
        saveGenerateOutputs(model, storage, List.of(position("Q1")));
    }

    /** Records every Generate step of the build as having produced {@code objects} for the
     *  current model, acquiring nothing extra. */
    private static void saveGenerateOutputs(GeneratedProjectModel model, DomainStorage storage,
                                            List<WikidataDynamicObject> objects)
            throws Exception {
        for (BuildOperation operation : model.buildOperations()) {
            if (operation.kind() != BuildOperation.Kind.GENERATE_PROJECT) continue;
            GenerationStepOutputStore.of(storage, model.name(), operation.declarationId())
                    .save(model, objects, List.of(), SelfReferenceLedger.EMPTY,
                            datasource.graph.GraphDiscoveryState.EMPTY, java.util.Map.of());
        }
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
        saveGenerateOutputs(model, storage, List.of(position("Q1"), position("Q2")));
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
