package wikidata.explore.generation;

import dataset.DomainStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wikidata.explore.extract.SnapshotDomain;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;
import wikidata.explore.model.GeneratedProjectModel;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saving a project is an operation with no window in it: it plans every file before
 * writing, the plan names exactly what the write produces, and a caller decides about
 * the warnings. The desktop Save is one caller; a headless build is another.
 */
class ProjectSaveTest {

    @TempDir Path root;

    /** Every line the plan shows names a file the write then produces — the dialog and
     *  the write are one expression, not two that agree. */
    @Test void thePlanNamesExactlyTheFilesTheWriteProduces() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = domain();
        wikidata.explore.model.GeneratedClassModel graph = model.getOrCreateClass("StarGraph");
        graph.classKind(wikidata.explore.model.ClassKind.GRAPH);
        graph.graphSource(new wikidata.explore.model.GraphClassSource(
                new datasource.graph.GraphDiscoveryConfiguration.StartNode("Star", "",
                        datasource.graph.GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY),
                List.of(new datasource.graph.GraphDiscoveryConfiguration.NextNode(
                        new datasource.graph.GraphRelation("wikidata", "P31"),
                        datasource.graph.GraphTraversalDirection.OUTGOING,
                        datasource.graph.GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION,
                        "Star", null))));
        GraphDiscoveryResultStore.Artifact annotations = annotationSet(model);

        ProjectSave save = ProjectSave.plan(new ProjectSave.Input(model,
                run(model, member("Q1"), member("Q2")), null, List.of(annotations)), storage);
        List<String> planned = save.planLines();
        ProjectSave.Result result = save.write();

        List<File> files = List.of(storage.modelFile(model.name()),
                storage.ruleTreeFile(model.name()), storage.snapshotFile(model.name()),
                storage.registryFile(), storage.countsFile(model.name()),
                GraphDiscoveryResultStore.destination(storage, model.name(), "StarGraph"),
                storage.constructManifestFile(model.name()));
        for (File file : files) {
            assertTrue(file.isFile(), "written: " + file);
            assertTrue(planned.stream().anyMatch(line -> line.contains(file.getPath())),
                    "planned: " + file + " in " + planned);
            assertTrue(result.report().stream().anyMatch(line -> line.contains(file.getPath())),
                    "reported: " + file);
        }
        assertEquals(2, result.instancesWritten());
        assertTrue(save.warnings().isEmpty());
    }

    /** A model's snapshot is local working data: registered so it can be loaded, but not
     *  served. */
    @Test void aModelIsRegisteredButNotServed() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = domain();
        model.projectKind(GeneratedProjectModel.ProjectKind.MODEL);

        ProjectSave save = ProjectSave.plan(new ProjectSave.Input(model,
                run(model, member("Q1")), null, List.of()), storage);
        save.write();

        assertTrue(save.planLines().stream().anyMatch(line -> line.startsWith("Registry")
                && line.contains("not served")));
        assertFalse(quiz.DatasetRegistry.load(storage.registryFile())
                .datasets().getFirst().served());
        assertTrue(storage.snapshotFile(model.name()).isFile());
    }

    /** A domain turned into a model keeps one registry row, no longer served — the same
     *  state TransformApp's save writes for a model — and keeps its local instances. */
    @Test void changingADomainToAModelStopsServingItWithoutLosingItsRow()
            throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = domain();
        ProjectSave.plan(new ProjectSave.Input(model,
                run(model, member("Q1")), null, List.of()), storage).write();
        assertTrue(quiz.DatasetRegistry.load(storage.registryFile())
                .datasets().getFirst().served());

        model.projectKind(GeneratedProjectModel.ProjectKind.MODEL);
        ProjectSave save = ProjectSave.plan(new ProjectSave.Input(model,
                run(model, member("Q1")), null, List.of()), storage);
        assertTrue(save.planLines().stream().anyMatch(line -> line.contains("not served")),
                save.planLines().toString());
        save.write();

        List<quiz.DatasetRegistry.Dataset> rows =
                quiz.DatasetRegistry.load(storage.registryFile()).datasets();
        assertEquals(1, rows.size());
        assertFalse(rows.getFirst().served());
        assertTrue(storage.snapshotFile(model.name()).isFile(),
                "the model keeps its local working instances");
    }

    /**
     * With nothing loaded, Save writes the saved snapshot again under the current
     * inventory — a class renamed since that Save keeps its members (#309).
     */
    @Test void withNoRunTheSavedSnapshotIsReprojectedAndARenameKeepsItsMembers()
            throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = domain();
        ProjectSave.plan(new ProjectSave.Input(model, run(model, member("Q1"), member("Q2")),
                null, List.of()), storage).write();

        model.renameClass("Star", "CelestialBody");
        ProjectSave.Result result = ProjectSave.plan(
                new ProjectSave.Input(model, null, null, List.of()), storage).write();

        assertEquals(2, result.instancesWritten());
        assertTrue(new WikidataDynamicObjectJsonStore().load(storage.snapshotFile(model.name()))
                .stream().allMatch(value -> value.directClassNames().contains("CelestialBody")));
    }

    /** A headless caller holding a run made before a rename gets the same restamp the
     *  desktop applies; the operation does not rely on the window having done it. */
    @Test void aRunStampedBeforeARenameIsSavedUnderTheNewName() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = domain();
        ProjectSave.Run run = run(model, member("Q1"));
        model.renameClass("Star", "CelestialBody");

        ProjectSave.Result result = ProjectSave.plan(
                new ProjectSave.Input(model, run, null, List.of()), storage).write();

        assertEquals(1, result.instancesWritten());
    }

    /** What the desktop asks about is data a headless caller can decide on. */
    @Test void staleInstancesAndDroppedTypesAreWarningsNotDialogs() throws Exception {
        DomainStorage storage = DomainStorage.in(root.toFile());
        GeneratedProjectModel model = domain();
        WikidataDynamicObject other = new WikidataDynamicObject("Q9", "Other");
        other.type("Galaxy");
        model.getOrCreateClass("Galaxy");
        ProjectSave.plan(new ProjectSave.Input(model, run(model, member("Q1"), other),
                null, List.of()), storage).write();

        GeneratedProjectModel before = model.copy();
        model.rootClass().generationDepth(model.rootClass().generationDepth() + 1);
        ProjectSave save = ProjectSave.plan(new ProjectSave.Input(model,
                new ProjectSave.Run(List.of(member("Q1")), List.of(), null, before),
                null, List.of()), storage);

        assertEquals(List.of(ProjectSave.Warning.Kind.STALE_INSTANCES,
                        ProjectSave.Warning.Kind.TYPES_DROPPED),
                save.warnings().stream().map(ProjectSave.Warning::kind).toList());
        assertTrue(save.warnings().get(1).message().contains("Galaxy"));
    }

    private static GeneratedProjectModel domain() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("Stars");
        model.rootClass().className("Star");
        return model;
    }

    private static ProjectSave.Run run(GeneratedProjectModel model,
                                       WikidataDynamicObject... members) {
        return new ProjectSave.Run(List.of(members), List.of(), null, model.copy());
    }

    private static WikidataDynamicObject member(String qid) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, "Star " + qid);
        value.type("Star");
        return value;
    }

    private static GraphDiscoveryResultStore.Artifact annotationSet(GeneratedProjectModel model) {
        WikidataDynamicObject annotation = new WikidataDynamicObject("Q2", "Star Q2");
        annotation.type("StarGraph");
        annotation.put(GraphDiscoveryResultStore.GRAPH_DECISION, "Accepted");
        List<WikidataDynamicObject> annotations = List.of(annotation);
        return new GraphDiscoveryResultStore.Artifact(model.name(), "StarGraph", "Star",
                annotations, List.of(), new SnapshotDomain(annotations,
                        GraphDiscoveryResultStore.fieldGraph("StarGraph", "Star", annotations)));
    }
}
