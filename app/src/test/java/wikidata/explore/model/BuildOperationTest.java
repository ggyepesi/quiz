package wikidata.explore.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dataset.DomainStorage;
import wikidata.explore.generation.DomainSave;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import quiz.transform.FilterOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A project's build is stored configuration (directive 23): saved with the model, copied
 * with it, and attached to its graphs by declaration id. It says how the project is run,
 * not what it generates, so it never makes generated instances stale.
 */
class BuildOperationTest {

    @TempDir Path root;

    @Test void theBuildIsSavedWithTheModelInItsOrder() throws Exception {
        GeneratedProjectModel model = withGraph();
        String graphId = model.findClass("PositionGraph").declarationId();
        model.buildOperations(List.of(
                new BuildOperation(BuildOperation.Kind.GENERATE_PROJECT, ""),
                new BuildOperation(BuildOperation.Kind.RUN_GRAPH_CONSTRAINT, graphId),
                new BuildOperation(BuildOperation.Kind.SAVE_PROJECT_RESULT, "")));
        File file = root.resolve("offices.model.json").toFile();

        new GeneratedProjectModelStore().save(model, file);
        GeneratedProjectModel loaded = new GeneratedProjectModelStore().load(file);

        assertEquals(List.of("Generate project", "Run graph PositionGraph", "Save project"),
                loaded.buildOperations().stream().map(op -> op.describe(loaded)).toList());
        assertEquals(model.buildOperations().stream().map(BuildOperation::declarationId).toList(),
                loaded.buildOperations().stream().map(BuildOperation::declarationId).toList());
        assertEquals(3, model.copy().buildOperations().size());
    }

    @Test void editingTheBuildDoesNotMakeGeneratedInstancesStale() {
        GeneratedProjectModel model = withGraph();
        String before = DomainSave.signature(model);

        model.buildOperations(List.of(
                new BuildOperation(BuildOperation.Kind.GENERATE_PROJECT, "")));

        assertEquals(before, DomainSave.signature(model));
    }

    /** A graph decides which entities its result accepts, not what generation produces. */
    @Test void editingAGraphDoesNotMakeGeneratedInstancesStale() {
        GeneratedProjectModel model = withGraph();
        String before = DomainSave.signature(model);
        GeneratedClassModel graph = model.findClass("PositionGraph");

        graph.graphSource(new GraphClassSource(graph.graphSource().startNode(), List.of()));

        assertEquals(before, DomainSave.signature(model));
        org.junit.jupiter.api.Assertions.assertFalse(before.isBlank(),
                "a blank signature makes no claim at all, so it would pass for anything");
    }

    @Test void editingAFilterTransformationStalesItsOutputNotGeneration() {
        GeneratedProjectModel model = withGraph();
        GeneratedClassModel output = new GeneratedClassModel("SelectedPosition");
        output.baseClassName("Position");
        output.populationMode(ClassPopulationMode.MATERIALIZED_TRANSFORM);
        model.addClass(output);
        TransformationClassSource transformation = new TransformationClassSource();
        transformation.inputClassReference(model.rootClass().declarationId(), "Position");
        transformation.addStep(TransformationStep.filter(
                "holderCount", FilterOperator.GREATER_THAN, 0, null));
        output.transformationSource(transformation);
        String before = DomainSave.signature(model);
        assertEquals(before, DomainSave.signature(model),
                "taking a signature twice must not mutate its own input");

        output.transformationSource().steps(List.of(
                TransformationStep.filter(
                        "holderCount", FilterOperator.GREATER_THAN, 5, null)));

        assertEquals(before, DomainSave.signature(model));
    }

    @Test void aRenamedGraphKeepsItsBuildStep() {
        GeneratedProjectModel model = withGraph();
        model.buildOperations(List.of(new BuildOperation(
                BuildOperation.Kind.APPLY_GRAPH_DECISIONS,
                model.findClass("PositionGraph").declarationId())));

        model.renameClass("PositionGraph", "OfficeGraph");

        assertEquals("Apply graph decisions OfficeGraph",
                model.buildOperations().getFirst().describe(model));
    }

    @Test void aRenamedMaterializedClassKeepsItsBuildStepPopulationAndArtifactPath() {
        GeneratedProjectModel model = withGraph();
        GeneratedClassModel output = new GeneratedClassModel("SelectedPosition");
        output.baseClassName("Position");
        output.populationMode(ClassPopulationMode.MATERIALIZED_TRANSFORM);
        TransformationClassSource source = new TransformationClassSource();
        source.inputClassReference(model.rootClass().declarationId(), "Position");
        source.addStep(TransformationStep.filter(
                "holderCount", FilterOperator.GREATER_THAN, 0, null));
        output.transformationSource(source);
        model.addClass(output);
        PopulationSelection population = new PopulationSelection("SelectedPositions");
        population.className(output.className());
        population.instanceQids(List.of("Q1"));
        model.addSelection(population);
        model.buildOperations(List.of(new BuildOperation(
                BuildOperation.Kind.RUN_TRANSFORMATION, output.declarationId())));
        DomainStorage storage = DomainStorage.in(root.toFile());
        File before = storage.materializedPopulationFile(
                model.name(), output.declarationId());

        model.renameClass("SelectedPosition", "CuratedPosition");

        assertEquals("Run transformation CuratedPosition",
                model.buildOperations().getFirst().describe(model));
        assertEquals("CuratedPosition", population.className());
        assertEquals(before, storage.materializedPopulationFile(
                model.name(), output.declarationId()));
    }

    @Test void dependencyNamesItsOwningProjectAndSurvivesPersistence() throws Exception {
        GeneratedProjectModel model = withGraph();
        model.buildOperations(List.of(BuildOperation.dependency("Historical Positions")));
        File file = root.resolve("history.model.json").toFile();

        new GeneratedProjectModelStore().save(model, file);
        BuildOperation loaded = new GeneratedProjectModelStore().load(file)
                .buildOperations().getFirst();

        assertEquals("Historical Positions", loaded.targetProjectName());
        assertEquals("Build dependency Historical Positions", loaded.describe(model));
    }

    private static GeneratedProjectModel withGraph() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("Offices");
        model.rootClass().className("Position");
        GeneratedClassModel graph = model.getOrCreateClass("PositionGraph");
        graph.classKind(ClassKind.GRAPH);
        graph.graphSource(new GraphClassSource(
                new datasource.graph.GraphDiscoveryConfiguration.StartNode("Position", "",
                        datasource.graph.GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY),
                List.of(new datasource.graph.GraphDiscoveryConfiguration.NextNode(
                        new datasource.graph.GraphRelation("wikidata", "P31"),
                        datasource.graph.GraphTraversalDirection.OUTGOING,
                        datasource.graph.GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION,
                        "Position", null))));
        return model;
    }
}
