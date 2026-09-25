package dataset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wikidata.explore.model.ClassKind;
import wikidata.explore.model.ConstructInventory;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.PopulationSelection;
import wikidata.explore.model.VocabularySelection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Save makes the disk inventory current and removes only artifacts it no longer owns. */
class ConstructManifestTest {
    @TempDir Path temporary;

    @Test void reconciliationTreatsEveryConstructAsOneInventoryEntry() throws Exception {
        DomainStorage storage = DomainStorage.in(temporary.toFile());
        GeneratedProjectModel project = project();
        Files.createDirectories(storage.directory(project.name()).toPath());
        Files.writeString(storage.modelFile(project.name()).toPath(), "{}");
        Files.writeString(storage.snapshotFile(project.name()).toPath(), "{}");
        Path graph = storage.directory(project.name()).toPath()
                .resolve("positiongraph.graph.snapshot.json");
        Files.writeString(graph, "{}");

        storage.reconcileConstructs(project.name(), ConstructInventory.of(project));

        DomainStorage.ConstructManifest saved = storage.constructManifest(project.name());
        assertEquals(4, saved.constructs.size());
        assertEquals(List.of(
                ConstructInventory.Kind.CLASS,
                ConstructInventory.Kind.GRAPH,
                ConstructInventory.Kind.POPULATION,
                ConstructInventory.Kind.SELECTION),
                saved.constructs.stream().map(value -> value.kind).toList());
    }

    @Test void removingOneGraphDeletesOnlyItsSnapshotAtSave() throws Exception {
        DomainStorage storage = DomainStorage.in(temporary.toFile());
        GeneratedProjectModel project = project();
        Files.createDirectories(storage.directory(project.name()).toPath());
        Files.writeString(storage.modelFile(project.name()).toPath(), "{}");
        Files.writeString(storage.snapshotFile(project.name()).toPath(), "main");
        Path graph = storage.directory(project.name()).toPath()
                .resolve("positiongraph.graph.snapshot.json");
        Files.writeString(graph, "graph");
        storage.reconcileConstructs(project.name(), ConstructInventory.of(project));
        project.removeClass(project.findClass("PositionGraph"));

        List<java.io.File> removed = storage.reconcileConstructs(
                project.name(), ConstructInventory.of(project));

        assertEquals(List.of(graph.toFile()), removed);
        assertFalse(Files.exists(graph));
        assertTrue(storage.snapshotFile(project.name()).isFile(),
                "an unrelated class snapshot is not deleted");
        assertEquals(3, storage.constructManifest(project.name()).constructs.size());
    }

    @Test void removingAPopulationUsesTheSameInventoryReconciliation() throws Exception {
        DomainStorage storage = DomainStorage.in(temporary.toFile());
        GeneratedProjectModel project = project();
        Files.createDirectories(storage.directory(project.name()).toPath());
        Files.writeString(storage.modelFile(project.name()).toPath(), "{}");
        Files.writeString(storage.snapshotFile(project.name()).toPath(), "main");
        storage.reconcileConstructs(project.name(), ConstructInventory.of(project));
        assertTrue(project.removeSelection("PositionsForHistory"));

        List<java.io.File> removed = storage.reconcileConstructs(
                project.name(), ConstructInventory.of(project));

        assertTrue(removed.isEmpty(), "a population owns no separate instance file");
        assertTrue(storage.snapshotFile(project.name()).isFile());
        assertEquals(List.of(
                ConstructInventory.Kind.CLASS,
                ConstructInventory.Kind.GRAPH,
                ConstructInventory.Kind.SELECTION),
                storage.constructManifest(project.name()).constructs.stream()
                        .map(value -> value.kind).toList());
    }

    private static GeneratedProjectModel project() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("Historical Positions");
        project.rootClass(new GeneratedClassModel("Position"));
        GeneratedClassModel graph = new GeneratedClassModel("PositionGraph");
        graph.classKind(ClassKind.GRAPH);
        project.addClass(graph);
        PopulationSelection population = new PopulationSelection("PositionsForHistory");
        population.className("Position");
        population.instanceQids(List.of("Q1"));
        project.addSelection(population);
        VocabularySelection selection = new VocabularySelection("PositionKinds");
        selection.valueQids(List.of("Q4164871"));
        project.addSelection(selection);
        return project;
    }
}
