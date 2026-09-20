package wikidata.explore.model;

import datasource.EntityRef;
import datasource.graph.GraphDiscoveryConfiguration;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.constraint.GraphEvidenceCondition;
import datasource.graph.constraint.GraphPath;
import datasource.graph.constraint.GraphRelationReaches;
import datasource.graph.constraint.GraphRelationReachesUnder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class GraphDiscoveryConfigurationPersistenceTest {
    @TempDir Path temp;

    @Test void startAndPopulationRolesAreIndependentAndRoundTrip() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("History");
        project.rootClass(new GeneratedClassModel("Position"));
        project.rootClass().seedQids().add("Q4164871");
        project.addClass(graphClass(project, "PositionValidity"));

        Path file = temp.resolve("history.model.json");
        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        store.save(project, file.toFile());
        GeneratedProjectModel loaded = store.load(file.toFile());

        GeneratedClassModel restoredClass = loaded.findClass("PositionValidity");
        assertEquals(ClassKind.GRAPH, restoredClass.classKind(),
                "the kind is stored, so a reloaded graph class is still one");
        GraphDiscoveryConfiguration restored =
                restoredClass.graphSource().configurationFor(restoredClass.className());
        assertEquals("PositionValidity", restored.name(),
                "the run is named by the class that declares it");
        assertEquals(GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY,
                restored.startNode().use());
        assertEquals("Position", restored.startNode().qidSourceClass());
        assertEquals(GraphTraversalDirection.INCOMING,
                restored.nextNodes().getFirst().directionFromPrevious());
        assertEquals(GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION,
                restored.nextNodes().getFirst().use());
        assertEquals("Position", restored.nextNodes().getFirst().populationClass());
        assertEquals("P1001", restored.nextNodes().getFirst().evidenceCondition()
                .evidencePaths().getFirst().relation().relationId());
        assertEquals("P31", restored.nextNodes().getFirst().evidenceCondition()
                .tests().getFirst().relation().relationId());
        assertEquals(GraphEvidenceCondition.ReviewDisposition.EXCLUDE_AND_REPORT,
                restored.nextNodes().getFirst().evidenceCondition().reviewDisposition());
    }

    @Test void aGeneralisingTestSurvivesBeingSavedAndReloaded() throws Exception {
        // A sealed hierarchy persisted by a "kind" discriminator fails at LOAD, not at
        // compile: a subtype missing from the mixin writes happily and comes back as an
        // unreadable type, so the condition would quietly disappear from a saved graph.
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("History");
        project.rootClass(new GeneratedClassModel("Position"));
        GeneratedClassModel generalising = new GeneratedClassModel("PositionGraph");
        generalising.graphSource(new GraphClassSource(
                new GraphDiscoveryConfiguration.StartNode("Position",
                        GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY),
                List.of(new GraphDiscoveryConfiguration.NextNode(
                        new GraphRelation("wikidata", "P31"),
                        GraphTraversalDirection.INCOMING,
                        GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION, "Position",
                        new GraphEvidenceCondition("Node evidence",
                                List.of(GraphPath.direct(new GraphRelation("wikidata", "P1001"),
                                        GraphTraversalDirection.OUTGOING)),
                                List.of(GraphRelationReachesUnder.of(
                                        new GraphRelation("wikidata", "P31"),
                                        GraphTraversalDirection.OUTGOING,
                                        datasource.EntityRef.wikidata("Q7275"),
                                        new GraphRelation("wikidata", "P279"))),
                                GraphEvidenceCondition.ReviewDisposition
                                        .EXCLUDE_AND_REPORT)))));
        project.addClass(generalising);

        Path file = temp.resolve("generalising.model.json");
        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        store.save(project, file.toFile());
        GeneratedProjectModel loaded = store.load(file.toFile());

        var test = loaded.findClass("PositionGraph").graphSource().nextNodes().getFirst()
                .evidenceCondition().tests().getFirst();
        assertInstanceOf(GraphRelationReachesUnder.class, test,
                "the generalising test must come back as itself");
        GraphRelationReachesUnder under = (GraphRelationReachesUnder) test;
        assertEquals("Q7275", under.entity().id());
        assertEquals("P279", under.via().relationId());
        assertEquals(GraphRelationReachesUnder.DEFAULT_MAXIMUM_DEPTH, under.maximumDepth());
    }

    @Test void projectCopyCarriesTheAuthoredGraph() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addClass(graphClass(project, "PositionValidity"));

        GeneratedClassModel copied = project.copy().findClass("PositionValidity");

        assertEquals(configuration(), copied.graphSource().configurationFor("PositionValidity"),
                "a run handed a snapshot must get the graph the snapshot's class declares");
    }

    @Test void classRenameRetargetsBothGraphUses() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass(new GeneratedClassModel("Position"));
        project.addClass(graphClass(project, "PositionValidity"));

        project.renameClass("Position", "Office");

        GraphClassSource graph = project.findClass("PositionValidity").graphSource();
        assertEquals("Office", graph.startNode().qidSourceClass());
        assertEquals("Office", graph.nextNodes().getFirst().populationClass());
    }

    /**
     * Renaming the graph class renames the run, and the annotation set with it.
     *
     * <p>The name used to live on the configuration and be edited as free text, which is
     * how a run saved as PositionFilter came to sit beside a model calling itself
     * GraphConstraint. It is the class name now, so there is one name and the rename
     * path every other construct uses moves it.
     */
    @Test void renamingTheGraphClassRenamesTheRunItDeclares() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass(new GeneratedClassModel("Position"));
        project.addClass(graphClass(project, "PositionValidity"));

        project.renameClass("PositionValidity", "PositionFilter");

        GeneratedClassModel renamed = project.findClass("PositionFilter");
        assertEquals("PositionFilter",
                renamed.graphSource().configurationFor(renamed.className()).name());
    }

    /** A project may declare several graphs; the singleton allowed exactly one. */
    @Test void aProjectMayDeclareMoreThanOneGraph() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass(new GeneratedClassModel("Position"));
        project.addClass(graphClass(project, "PositionValidity"));
        project.addClass(graphClass(project, "PositionHolders"));

        assertEquals(List.of("PositionValidity", "PositionHolders"),
                project.graphClasses().stream()
                        .map(GeneratedClassModel::className).toList());
    }

    private static GeneratedClassModel graphClass(
            GeneratedProjectModel project, String name) {
        GeneratedClassModel graphClass = new GeneratedClassModel(name);
        GraphDiscoveryConfiguration authored = configuration();
        graphClass.graphSource(new GraphClassSource(
                authored.startNode(), authored.nextNodes()));
        return graphClass;
    }

    private static GraphDiscoveryConfiguration configuration() {
        return new GraphDiscoveryConfiguration("PositionValidity",
                new GraphDiscoveryConfiguration.StartNode("Position",
                        GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY),
                List.of(new GraphDiscoveryConfiguration.NextNode(
                        new GraphRelation("wikidata", "P279"),
                        GraphTraversalDirection.INCOMING,
                        GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION,
                        "Position",
                        new GraphEvidenceCondition("Node evidence",
                                List.of(GraphPath.direct(
                                        new GraphRelation("wikidata", "P1001"),
                                        GraphTraversalDirection.OUTGOING)),
                                List.of(new GraphRelationReaches(
                                        new GraphRelation("wikidata", "P31"),
                                        GraphTraversalDirection.OUTGOING,
                                        EntityRef.wikidata("Q4164871"))),
                                GraphEvidenceCondition.ReviewDisposition
                                        .EXCLUDE_AND_REPORT))));
    }
}
