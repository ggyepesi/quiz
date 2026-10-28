package wikidata.explore.build;

import dataset.DomainStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.transform.FilterOperator;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.generation.ProjectSave;
import wikidata.explore.model.BuildOperation;
import wikidata.explore.model.ClassPopulationMode;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;
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
}
