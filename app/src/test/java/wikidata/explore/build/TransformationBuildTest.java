package wikidata.explore.build;

import dataset.DomainStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.transform.FilterOperator;
import wikidata.explore.extract.SnapshotDomain;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;
import wikidata.explore.generation.GraphDiscoveryResultStore;
import wikidata.explore.generation.GraphResults;
import wikidata.explore.generation.ProjectSave;
import wikidata.explore.model.BuildOperation;
import wikidata.explore.model.ClassKind;
import wikidata.explore.model.ClassPopulationMode;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;
import wikidata.explore.model.GraphClassSource;
import wikidata.explore.model.PopulationSelection;
import wikidata.explore.model.TransformationClassSource;
import wikidata.explore.model.TransformationStep;
import wikidata.explore.transform.MaterializedPopulationStore;
import work.QueryContext;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransformationBuildTest {
    @TempDir Path root;

    @Test void headlessBuildMaterializesThenPublishesWithoutRegenerating() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = model();
        WikidataDynamicObject one = position("Q1", 2);
        WikidataDynamicObject two = position("Q2", 0);
        ProjectSave.plan(new ProjectSave.Input(model,
                new ProjectSave.Run(List.of(one, two), List.of(), null, model.copy()),
                null, List.of()), storage).write();
        generated(model, storage, one, two);

        ProjectBuild.Report report = ProjectBuild.run(
                model, storage, new QueryContext(), null);

        assertEquals(ProjectBuild.State.CURRENT, report.outcome());
        assertEquals(List.of(ProjectBuild.Action.REUSED, ProjectBuild.Action.RAN,
                        ProjectBuild.Action.RAN, ProjectBuild.Action.RAN),
                report.steps().stream().map(ProjectBuild.Done::action).toList());
        PopulationSelection published = (PopulationSelection)
                model.findSelection("PositionWithHoldersPopulation");
        assertEquals(List.of("Q1"), published.instanceQids());
        assertTrue(storage.materializedPopulationFile(
                model.name(), model.findClass("PositionWithHolders").declarationId()).isFile());
        assertTrue(storage.publishedPopulationFile(
                model.name(), published.declarationId()).isFile(),
                "the publication is the selection's own artifact");
        GeneratedProjectModel saved = new GeneratedProjectModelStore().load(
                storage.modelFile(model.name()));
        assertEquals(List.of("Q1"), ((PopulationSelection) saved.findSelection(
                "PositionWithHoldersPopulation")).instanceQids());
        assertEquals(storage.savedSnapshotSignature(model.name()),
                wikidata.explore.generation.DomainSave.signature(saved),
                "the publication output must not invalidate its producer snapshot");
        List<ProjectBuild.Step> secondPlan = ProjectBuild.plan(saved, storage);
        assertEquals(List.of(ProjectBuild.State.CURRENT, ProjectBuild.State.CURRENT,
                        ProjectBuild.State.CURRENT, ProjectBuild.State.CURRENT),
                secondPlan.stream().map(ProjectBuild.Step::state).toList(),
                secondPlan.stream().map(ProjectBuild.Step::explain).toList().toString());
    }

    @Test void dependencyBuildRefreshesTheOwningModelBeforeTheConsumer() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel owner = model();
        ProjectSave.plan(new ProjectSave.Input(owner,
                new ProjectSave.Run(List.of(position("Q1", 2), position("Q2", 0)), List.of(), null,
                        owner.copy()), null, List.of()), storage).write();
        generated(owner, storage, position("Q1", 2), position("Q2", 0));
        ProjectBuild.run(owner, storage, new QueryContext(), null);

        GeneratedProjectModel changed = new GeneratedProjectModelStore().load(
                storage.modelFile(owner.name()));
        changed.findClass("PositionWithHolders").transformationSource().steps(List.of(
                TransformationStep.filter(
                        "holderCount", FilterOperator.GREATER_THAN, 1, null)));
        new GeneratedProjectModelStore().save(changed, storage.modelFile(changed.name()));

        GeneratedProjectModel consumer = new GeneratedProjectModel();
        consumer.name("History");
        consumer.buildOperations(List.of(BuildOperation.dependency(owner.name())));
        java.util.List<String> log = new java.util.ArrayList<>();
        ProjectBuild.Report report = ProjectBuild.run(
                consumer, storage, new QueryContext(), log::add);

        assertEquals(ProjectBuild.State.CURRENT, report.outcome(), log.toString());
        assertEquals(ProjectBuild.Action.RAN, report.steps().getFirst().action());
        GeneratedProjectModel rebuilt = new GeneratedProjectModelStore().load(
                storage.modelFile(owner.name()));
        GeneratedClassModel rebuiltOutput = rebuilt.findClass("PositionWithHolders");
        assertEquals(MaterializedPopulationStore.signature(
                        rebuiltOutput),
                MaterializedPopulationStore.metadata(rebuilt,
                        rebuiltOutput, storage)
                        .transformationSignature());
    }

    /** Publishing is prepared by its build step but crosses the import boundary only when
     *  Save project succeeds. A later save refusal must leave the previous complete
     *  publication and saved selection together. */
    @Test void aRefusedSaveDoesNotReplaceTheLastCompletePublication() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = model();
        ProjectSave.plan(new ProjectSave.Input(model,
                new ProjectSave.Run(List.of(position("Q1", 2), position("Q2", 0)),
                        List.of(), null, model.copy()), null, List.of()), storage).write();
        generated(model, storage, position("Q1", 2), position("Q2", 0));
        assertEquals(ProjectBuild.State.CURRENT, ProjectBuild.run(
                model, storage, new QueryContext(), null).outcome());
        PopulationSelection population = (PopulationSelection)
                model.findSelection("PositionWithHoldersPopulation");

        List<WikidataDynamicObject> changedSnapshot = new java.util.ArrayList<>(
                new WikidataDynamicObjectJsonStore().load(
                        storage.snapshotFile(model.name())));
        WikidataDynamicObject addedAfterTheLastPublication = position("Q3", 4);
        addedAfterTheLastPublication.assignClass("PositionWithHolders");
        changedSnapshot.add(addedAfterTheLastPublication);
        new WikidataDynamicObjectJsonStore().saveWithFieldGraph(
                changedSnapshot, storage.snapshotFile(model.name()), model);

        model.findClass("PositionWithHolders").transformationSource().steps(List.of(
                TransformationStep.filter(
                        "holderCount", FilterOperator.GREATER_THAN, 1, null)));
        model.rootClass().seedQids().add("Q99");
        BuildOperation transform = model.buildOperations().stream()
                .filter(operation -> operation.kind()
                        == BuildOperation.Kind.RUN_TRANSFORMATION)
                .findFirst().orElseThrow();
        BuildOperation publish = model.buildOperations().stream()
                .filter(operation -> operation.kind()
                        == BuildOperation.Kind.PUBLISH_POPULATION)
                .findFirst().orElseThrow();
        BuildOperation save = model.buildOperations().stream()
                .filter(operation -> operation.kind()
                        == BuildOperation.Kind.SAVE_PROJECT_RESULT)
                .findFirst().orElseThrow();
        model.buildOperations(List.of(transform, publish, save));

        ProjectBuild.Report refused = ProjectBuild.run(
                model, storage, new QueryContext(), null);

        assertEquals(ProjectBuild.State.FAILED, refused.outcome());
        assertEquals(List.of(ProjectBuild.Action.RAN, ProjectBuild.Action.RAN,
                        ProjectBuild.Action.STOPPED),
                refused.steps().stream().map(ProjectBuild.Done::action).toList());
        assertEquals(List.of("Q1"), MaterializedPopulationStore.loadPublished(
                        model, population, storage).stream()
                        .map(WikidataDynamicObject::getIdentifier).toList(),
                "Q3 was only staged; the refused save did not publish it");
        GeneratedProjectModel saved = new GeneratedProjectModelStore().load(
                storage.modelFile(model.name()));
        assertEquals(List.of("Q1"), ((PopulationSelection) saved.findSelection(
                "PositionWithHoldersPopulation")).instanceQids(),
                "the saved selection still describes the last complete publication");
    }

    /** Damaged metadata used to read as "no materialized population", sending the user to
     *  rebuild an output that is present. It now fails naming the file it cannot read. */
    @Test void unreadableMetadataIsReportedAsUnreadableNotMissing() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = model();
        ProjectSave.plan(new ProjectSave.Input(model,
                new ProjectSave.Run(List.of(position("Q1", 2)), List.of(), null, model.copy()),
                null, List.of()), storage).write();
        generated(model, storage, position("Q1", 2));
        GeneratedClassModel output = model.findClass("PositionWithHolders");
        MaterializedPopulationStore.save(model, output, List.of(position("Q1", 2)), storage,
                storage.savedSnapshotSignature(model.name()));
        java.io.File metadata = storage.materializedPopulationMetadataFile(
                model.name(), output.declarationId());
        java.nio.file.Files.writeString(metadata.toPath(), "{ not json");

        ProjectBuild.Step transform = ProjectBuild.plan(model, storage).stream()
                .filter(step -> step.operation().kind() == BuildOperation.Kind.RUN_TRANSFORMATION)
                .findFirst().orElseThrow();

        assertEquals(ProjectBuild.State.FAILED, transform.state(), transform.explain());
        assertTrue(transform.explain().contains("Cannot read population metadata "
                + metadata.getPath()), transform.explain());
    }

    @Test void aPopulationIsWrittenWithoutLeavingTemporaryFiles() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = model();
        GeneratedClassModel output = model.findClass("PositionWithHolders");

        List<String> written = MaterializedPopulationStore.save(
                model, output, List.of(position("Q1", 2)), storage, "input");

        assertEquals(List.of("Q1"), MaterializedPopulationStore.load(model, output, storage)
                .stream().map(WikidataDynamicObject::getIdentifier).toList());
        assertEquals("input", MaterializedPopulationStore.metadata(model, output, storage)
                .inputSnapshotSignature());
        for (String path : written) {
            assertTrue(!new java.io.File(path + ".tmp").exists(), path + ".tmp is left behind");
        }
    }

    /**
     * #432: Historical Positions reused a Generate, narrowing Apply and transformation,
     * then reran only the final additive Apply. Reuse treated the two state changes as
     * no-ops, so Save replaced 961 PositionWithHolders with the graph's 71 identities.
     * A missing transformation member makes the last transformation stale, and rebuilding
     * it replays every current state mutation after the Generate checkpoint.
     */
    @Test void aLaterRepairReplaysCurrentMutationsAfterTheReusedGenerate() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = model();
        GeneratedClassModel output = model.findClass("PositionWithHolders");
        GeneratedClassModel graph = model.getOrCreateClass("PositionGraph");
        graph.classKind(ClassKind.GRAPH);
        graph.graphSource(new GraphClassSource(
                new datasource.graph.GraphDiscoveryConfiguration.StartNode(
                        "Position", "",
                        datasource.graph.GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY),
                List.of(new datasource.graph.GraphDiscoveryConfiguration.NextNode(
                        new datasource.graph.GraphRelation("wikidata", "P31"),
                        datasource.graph.GraphTraversalDirection.OUTGOING,
                        datasource.graph.GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION,
                        "Position", null))));
        BuildOperation generate = model.buildOperations().getFirst();
        BuildOperation apply = new BuildOperation(
                BuildOperation.Kind.APPLY_GRAPH_DECISIONS, graph.declarationId());
        BuildOperation transform = new BuildOperation(
                BuildOperation.Kind.RUN_TRANSFORMATION, output.declarationId());
        model.buildOperations(List.of(generate, apply, transform,
                new BuildOperation(BuildOperation.Kind.SAVE_PROJECT_RESULT, "")));

        WikidataDynamicObject accepted = position("Q1", 2);
        WikidataDynamicObject rejected = position("Q2", 2);
        GraphResults results = appliedNarrowing(model, storage, graph, accepted, rejected);
        WikidataDynamicObject incompleteSavedOutput = position("Q3", 2);
        incompleteSavedOutput.assignSubclass(output.className(), "Position");
        ProjectSave.plan(new ProjectSave.Input(model,
                new ProjectSave.Run(List.of(incompleteSavedOutput), List.of(), null, model.copy()),
                null, results.all()), storage).write();
        generated(model, storage, accepted, rejected);
        WikidataDynamicObject materialized = position("Q1", 2);
        materialized.assignSubclass(output.className(), "Position");
        MaterializedPopulationStore.save(model, output, List.of(materialized), storage,
                storage.savedSnapshotSignature(model.name()));

        List<ProjectBuild.Step> plan = ProjectBuild.plan(model, storage);

        assertEquals(List.of(ProjectBuild.State.CURRENT, ProjectBuild.State.STALE,
                        ProjectBuild.State.STALE, ProjectBuild.State.STALE),
                plan.stream().map(ProjectBuild.Step::state).toList(),
                plan.stream().map(ProjectBuild.Step::explain).toList().toString());
        assertTrue(plan.get(1).because().getFirst().contains(
                wikidata.explore.generation.GenerationStepOutputStore.of(
                        storage, model.name(), generate.declarationId())
                        .snapshotFile().getPath()), plan.get(1).explain());
        assertTrue(plan.get(2).because().stream().anyMatch(reason -> reason.contains(
                "1 materialized PositionWithHolders identity is not a member")),
                plan.get(2).explain());

        ProjectBuild.Report repaired = ProjectBuild.run(
                model, storage, new QueryContext(), null);

        assertEquals(ProjectBuild.State.CURRENT, repaired.outcome(),
                repaired.steps().toString());
        assertEquals(List.of(ProjectBuild.Action.REUSED, ProjectBuild.Action.RAN,
                        ProjectBuild.Action.RAN, ProjectBuild.Action.RAN),
                repaired.steps().stream().map(ProjectBuild.Done::action).toList());
        List<WikidataDynamicObject> saved = new WikidataDynamicObjectJsonStore().load(
                storage.snapshotFile(model.name()));
        assertTrue(saved.stream().anyMatch(value -> "Q1".equals(value.getIdentifier())
                && value.directClassNames().contains("PositionWithHolders")));
        assertTrue(saved.stream().noneMatch(value -> "Q2".equals(value.getIdentifier())
                && value.directClassNames().contains("PositionWithHolders")),
                "the replayed narrowing, not the unfiltered Generate output, feeds the transform");
    }

    /** Records the build's Generate step as having produced {@code objects} for the current
     *  model, so the build reuses it instead of reaching Wikidata. */
    private static void generated(GeneratedProjectModel model, DomainStorage storage,
                                  WikidataDynamicObject... objects) throws Exception {
        for (BuildOperation operation : model.buildOperations()) {
            if (operation.kind() != BuildOperation.Kind.GENERATE_PROJECT) continue;
            wikidata.explore.generation.GenerationStepOutputStore.of(
                            storage, model.name(), operation.declarationId())
                    .save(model, List.of(objects), List.of(),
                            wikidata.explore.transform.SelfReferenceLedger.EMPTY,
                            datasource.graph.GraphDiscoveryState.EMPTY, java.util.Map.of());
        }
    }

    private static GeneratedProjectModel model() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("Positions");
        model.rootClass().className("Position");
        model.rootClass().seedQids().addAll(List.of("Q1", "Q2"));
        GeneratedClassModel output = new GeneratedClassModel("PositionWithHolders");
        output.baseClassName("Position");
        output.populationMode(ClassPopulationMode.MATERIALIZED_TRANSFORM);
        model.addClass(output);
        TransformationClassSource transformation = new TransformationClassSource();
        transformation.inputClassReference(model.rootClass().declarationId(), "Position");
        transformation.addStep(TransformationStep.filter(
                "holderCount", FilterOperator.GREATER_THAN, 0, null));
        output.transformationSource(transformation);
        PopulationSelection population = new PopulationSelection(
                "PositionWithHoldersPopulation");
        population.className(output.className());
        model.addSelection(population);
        model.buildOperations(List.of(
                new BuildOperation(BuildOperation.Kind.GENERATE_PROJECT, ""),
                new BuildOperation(BuildOperation.Kind.RUN_TRANSFORMATION,
                        output.declarationId()),
                new BuildOperation(BuildOperation.Kind.PUBLISH_POPULATION,
                        population.declarationId()),
                new BuildOperation(BuildOperation.Kind.SAVE_PROJECT_RESULT, "")));
        return model;
    }

    private static WikidataDynamicObject position(String qid, int holders) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, qid);
        value.type("Position");
        value.put("holderCount", holders);
        return value;
    }

    private static GraphResults appliedNarrowing(
            GeneratedProjectModel model, DomainStorage storage, GeneratedClassModel graph,
            WikidataDynamicObject accepted, WikidataDynamicObject rejected) throws Exception {
        WikidataDynamicObject yes = annotation(graph.className(), accepted, "Accepted");
        WikidataDynamicObject no = annotation(graph.className(), rejected, "Rejected");
        List<WikidataDynamicObject> annotations = List.of(yes, no);
        GraphDiscoveryResultStore.Artifact plain =
                new GraphDiscoveryResultStore.Artifact(model.name(), graph.className(),
                        "Position", annotations, List.of(accepted, rejected),
                        new SnapshotDomain(annotations,
                                GraphDiscoveryResultStore.fieldGraph(
                                        graph.className(), "Position", annotations)));
        GraphResults results = new GraphResults(model, storage);
        results.record(graph, GraphDiscoveryResultStore.applied(plain));
        return results;
    }

    private static WikidataDynamicObject annotation(
            String graphClass, WikidataDynamicObject value, String decision) {
        WikidataDynamicObject annotation = new WikidataDynamicObject(
                value.getIdentifier(), value.getDisplayName());
        annotation.type(graphClass);
        annotation.put(GraphDiscoveryResultStore.GRAPH_DECISION, decision);
        annotation.put(GraphDiscoveryResultStore.ANNOTATED_INSTANCE, value);
        return annotation;
    }
}
