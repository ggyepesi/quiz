package wikidata.explore.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wikidata.explore.generation.DomainSave;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A project's build is stored configuration (directive 22): saved with the model, copied
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

    @Test void aRenamedGraphKeepsItsBuildStep() {
        GeneratedProjectModel model = withGraph();
        model.buildOperations(List.of(new BuildOperation(
                BuildOperation.Kind.APPLY_GRAPH_DECISIONS,
                model.findClass("PositionGraph").declarationId())));

        model.renameClass("PositionGraph", "OfficeGraph");

        assertEquals("Apply graph decisions OfficeGraph",
                model.buildOperations().getFirst().describe(model));
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
