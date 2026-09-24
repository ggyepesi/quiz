package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import datasource.EntityRef;
import datasource.graph.GraphDiscoveryConfiguration;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.constraint.GraphEvidenceCondition;
import datasource.graph.constraint.GraphEvidenceConditionResult;
import datasource.graph.constraint.GraphPath;
import datasource.graph.constraint.GraphRelationExists;
import datasource.graph.execution.GraphDiscoveryExecutor;
import graphview.GraphViewModel;
import objectview.render.Card;
import objectview.viewconfig.ViewConfig;
import process.swing.SwingProcessRunner;
import process.swing.workflow.ProcessWorkflowResults;
import quiz.transform.DynamicViewable;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.PopulationSelection;
import wikidata.explore.query.logical.ConfiguredGraphDiscoveryQuery;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphConstraintsPanelTest {

    @Test void graphStartIsOneChoiceBetweenALoadedClassAndASavedPopulation() {
        GeneratedProjectModel model = model();
        PopulationSelection positions = new PopulationSelection("PositionsForHistory");
        positions.className("Position");
        positions.instanceQids(List.of("Q1", "Q2"));
        model.addSelection(positions);
        GraphConstraintsPanel panel = graphPanel(model);
        wikidata.explore.extract.WikidataDynamicObject loaded =
                new wikidata.explore.extract.WikidataDynamicObject("Q9", "Position 9");
        loaded.type("Position");
        panel.loadedInstances(() -> List.of(loaded));
        JComboBox<?> inputs = named(panel, "graph.startInput", JComboBox.class);

        assertEquals(List.of("Class: Position · 1 loaded instances",
                        "Population: PositionsForHistory · Position · 2 instances"),
                java.util.stream.IntStream.range(0, inputs.getItemCount())
                        .mapToObj(i -> inputs.getItemAt(i).toString()).toList());
        inputs.setSelectedIndex(1);
        assertEquals("2 QIDs", named(panel, "graph.startQids", JLabel.class).getText());
    }

    /**
     * Selecting a graph class opens the graph editor, the way selecting any class opens
     * the editor for its kind.
     *
     * <p>It used to be a configuration section of the project's own, which is what held
     * the one graph a project could have.
     */
    @Test void aGraphClassOpensItsEditorLikeEveryOtherKind() {
        GeneratedProjectModel model = model();
        graphClass(model, "PositionGraph");
        ModelSourceWorkbenchPanel workbench = new ModelSourceWorkbenchPanel(model);

        workbench.edit(model.findClass("PositionGraph"));

        GraphConstraintsPanel panel = find(workbench, GraphConstraintsPanel.class);
        assertTrue(panel.isVisible());
        assertNotNull(button(panel, "Apply graph"));
        assertSame(model.findClass("PositionGraph"), panel.editing(),
                "the editor edits the selected class, not the project");
        assertNull(find(workbench, FieldSourcePanel.class)
                .getClientProperty("graph constraints"),
                "the graph editor is not encoded in field configuration");
    }

    @Test void eachGraphClassKeepsItsOwnCompletedResult() {
        GeneratedProjectModel model = model();
        GeneratedClassModel first = graphClass(model, "PositionGraph");
        GeneratedClassModel second = graphClass(model, "HolderGraph");
        GraphConstraintsPanel panel = new GraphConstraintsPanel(model);
        ConfiguredGraphDiscoveryQuery.Result result = resultFor("Position");

        panel.edit(first);
        panel.graphResults(result, "PositionGraph");
        panel.edit(second);
        assertNull(panel.lastGraphResult(),
                "selecting a graph with no result must not expose another graph's result");
        panel.graphResults(result, "HolderGraph");

        assertEquals(2, panel.graphResults().size(),
                "the project save boundary sees every completed graph result");
        assertEquals("HolderGraph", panel.lastGraphResult().type());
        panel.edit(first);
        assertEquals("PositionGraph", panel.lastGraphResult().type(),
                "Show instances follows the selected graph class");
    }

    @Test void savingUnchangedGraphConfigurationKeepsItsCompletedResult() {
        GeneratedProjectModel model = model();
        GeneratedClassModel graph = graphClass(model, "PositionGraph");
        graph.graphSource(new wikidata.explore.model.GraphClassSource(
                new GraphDiscoveryConfiguration.StartNode(
                        "Position", "",
                        GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY),
                List.of(outputNode("Position"))));
        GraphConstraintsPanel panel = new GraphConstraintsPanel(model);
        panel.edit(graph);
        panel.graphResults(resultFor("Position"), "PositionGraph");

        panel.applyPendingEdits();

        assertNotNull(panel.lastGraphResult(),
                "Save flushes unchanged controls; it must not invalidate the run");
    }

    @Test void loadedProjectPoolRestoresTheSavedGraphResultWithoutRerunning() {
        GeneratedProjectModel model = model();
        model.name("Historical Positions");
        GeneratedClassModel graphClass = graphClass(model, "GraphConstraint");
        graphClass.graphSource(new wikidata.explore.model.GraphClassSource(
                new GraphDiscoveryConfiguration.StartNode(
                        "Position", "", GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY),
                List.of(outputNode("Position"))));
        GraphDiscoveryResultStore.Artifact saved = GraphDiscoveryResultStore.artifact(
                model.name(), graphClass.className(), resultFor("Position"));
        GraphConstraintsPanel panel = new GraphConstraintsPanel(model);

        panel.restoreGraphResults(saved.instances());
        panel.edit(graphClass);

        assertNotNull(panel.lastGraphResult());
        assertEquals("GraphConstraint", panel.lastGraphResult().type());
        assertEquals(saved.instances().size(), panel.lastGraphResult().instances().size());
    }

    /**
     * The editor must be able to say everything the declaration holds. It showed the
     * first alternative edge in one property field, and applying rebuilt the list from
     * that field — so opening a replacement expansion that follows both `replaces` and
     * `replaced by`, then applying any unrelated change, silently dropped the second.
     */
    @Test void everyAlternativeEdgeSurvivesBeingEditedAndAppliedAgain() {
        GeneratedProjectModel model = model();
        GeneratedClassModel graph = graphClass(model, "PositionReplacementExpansion");
        java.util.List<GraphDiscoveryConfiguration.Edge> alternatives = java.util.List.of(
                new GraphDiscoveryConfiguration.Edge(
                        new datasource.graph.GraphRelation("wikidata", "P1366"),
                        datasource.graph.GraphTraversalDirection.INCOMING),
                new GraphDiscoveryConfiguration.Edge(
                        new datasource.graph.GraphRelation("wikidata", "P156"),
                        datasource.graph.GraphTraversalDirection.OUTGOING));
        graph.graphSource(new wikidata.explore.model.GraphClassSource(
                new GraphDiscoveryConfiguration.StartNode("Position", "",
                        GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY),
                java.util.List.of(new GraphDiscoveryConfiguration.NextNode(
                        new datasource.graph.GraphRelation("wikidata", "P1365"),
                        datasource.graph.GraphTraversalDirection.OUTGOING,
                        GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION,
                        "Position", null, alternatives,
                        GraphDiscoveryConfiguration.PopulationOperation.ADD, true))));
        GraphConstraintsPanel panel = graphPanel(model);
        panel.edit(graph);

        assertEquals(2, named(panel, "graph.alternativeList", JList.class)
                .getModel().getSize(), "the editor shows every alternative it holds");

        button(panel, "Apply graph").doClick();

        assertEquals(alternatives,
                graph.graphSource().nextNodes().getFirst().alternativeEdges(),
                "and applying keeps them all");
    }

    /**
     * A renamed graph class does not carry its old run forward, and the save dialog
     * names the file the save writes.
     *
     * <p>The declaration id keeps the annotation set owned by its class through a
     * rename — but ownership is not currency. The annotations are STAMPED with the name
     * the class had when they were produced, and the file is keyed by that name, so
     * re-filing them under the new one would write instances typed PositionGraph into a
     * set called PositionRelevance. It was worse than that before: the save dialog built
     * its path from the live names while the write took the artifact's recorded ones, so
     * a rename made the dialog promise data/wikidata/offices/positionrelevance… while
     * the save produced data/wikidata/historicalpositions/positiongraph… — the annotation
     * set written under one name and looked for under another, which is the whole failure
     * a graph class exists to prevent.
     */
    @Test void aRenamedGraphClassDoesNotCarryItsOldRunForward() {
        GeneratedProjectModel model = model();
        model.name("Historical Positions");
        GeneratedClassModel graphClass = graphClass(model, "PositionGraph");
        GraphConstraintsPanel panel = new GraphConstraintsPanel(model);
        panel.edit(graphClass);
        panel.graphResults(resultFor("Position"), "PositionGraph");

        GraphDiscoveryResultStore.Artifact ran = panel.lastGraphResult();
        assertEquals("data/wikidata/historicalpositions/positiongraph.graph.snapshot.json",
                GraphDiscoveryResultStore.destinationOf(ran).getPath(),
                "the one expression the save dialog and the write both take");

        model.renameClass("PositionGraph", "PositionRelevance");

        assertNull(panel.lastGraphResult(),
                "a run of PositionGraph is not PositionRelevance's result; running "
                        + "again replays the adjacency from the local store");
        assertTrue(panel.graphResults().isEmpty(),
                "and the save boundary is offered nothing it cannot file truthfully");
    }

    /** Renaming the project moves the annotation set the same way, for the same reason. */
    @Test void aRenamedProjectDoesNotCarryItsGraphRunsForward() {
        GeneratedProjectModel model = model();
        model.name("Historical Positions");
        GeneratedClassModel graphClass = graphClass(model, "PositionGraph");
        GraphConstraintsPanel panel = new GraphConstraintsPanel(model);
        panel.edit(graphClass);
        panel.graphResults(resultFor("Position"), "PositionGraph");
        assertNotNull(panel.lastGraphResult());

        model.name("Offices");

        assertNull(panel.lastGraphResult(),
                "the annotation set lives under the project directory, so the project's "
                        + "name is half of where it goes");
    }

    /**
     * One editor serves every graph class, so selecting the next one must not show the
     * previous one's draft.
     *
     * <p>The controls are fields of the panel, not of the class, and nothing rebuilt
     * them on selection: the edge property, the evidence relations and the tests left
     * behind by the graph you were just looking at stayed on screen over the next one's
     * name — and Apply would have written them onto it. Nothing in the suite could see
     * this; the result isolation test next door covers what was RUN, not what is typed.
     */
    @Test void selectingAnotherGraphClassStartsFromItsOwnSavedGraph() {
        GeneratedProjectModel model = model();
        GeneratedClassModel first = graphClass(model, "PositionGraph");
        GeneratedClassModel second = graphClass(model, "HolderGraph");
        GraphConstraintsPanel panel = new GraphConstraintsPanel(model);

        panel.edit(first);
        text(panel, "graph.edgeProperty").setText("P279");
        text(panel, "graph.evidenceProperty").setText("P1001");
        button(panel, "Add evidence relation").doClick();
        text(panel, "graph.testProperty").setText("P576");
        button(panel, "Add evidence test").doClick();
        assertEquals(1, named(panel, "graph.evidenceList", JList.class)
                .getModel().getSize());

        panel.edit(second);

        assertEquals("", text(panel, "graph.edgeProperty").getText(),
                "the next graph class starts from its own saved graph, not from the "
                        + "controls the previous one left");
        assertEquals(0, named(panel, "graph.evidenceList", JList.class)
                .getModel().getSize(),
                "evidence relations belong to the graph that declared them");
        assertEquals(0, named(panel, "graph.testsList", JList.class)
                .getModel().getSize(),
                "and so do its tests");
        assertNull(second.graphSource(),
                "leaving a draft behind must not write it onto the class selected next");
    }

    @Test void loadedClassInstancesAreShownButApplyingIsExplicit() {
        GeneratedProjectModel model = model();
        GraphConstraintsPanel panel = graphPanel(model);
        wikidata.explore.extract.WikidataDynamicObject loaded =
                new wikidata.explore.extract.WikidataDynamicObject("Q4164871", "Position");
        loaded.type("Position");
        panel.loadedInstances(() -> List.of(loaded));

        assertEquals("1 QID", named(panel, "graph.startQids", JLabel.class).getText());

        text(panel, "graph.edgeProperty").setText("P279");
        named(panel, "graph.edgeDirection", JComboBox.class).setSelectedIndex(1);
        text(panel, "graph.evidenceProperty").setText("P1001");
        button(panel, "Add evidence relation").doClick();
        text(panel, "graph.testProperty").setText("P576");
        button(panel, "Add evidence test").doClick();
        named(panel, "graph.reviewDisposition", JComboBox.class).setSelectedItem(
                GraphEvidenceCondition.ReviewDisposition.EXCLUDE_AND_REPORT);

        assertNull(graph(model),
                "editing the draft must not mutate the model");
        button(panel, "Apply graph").doClick();

        assertEquals("P279", graph(model).nextNodes()
                .getFirst().property().relationId());
        GraphEvidenceCondition evidence = graph(model).nextNodes()
                .getFirst().evidenceCondition();
        assertEquals("P1001", evidence.evidencePaths().getFirst().relation().relationId());
        assertEquals("P576", evidence.tests().getFirst().relation().relationId());
        assertEquals(GraphEvidenceCondition.ReviewDisposition.EXCLUDE_AND_REPORT,
                evidence.reviewDisposition());
    }

    @Test void graphPropertiesUseTheAlreadyLoadedCatalogueLabels() {
        GraphConstraintsPanel panel = graphPanel(model());
        panel.propertyCache(() -> java.util.Map.of("P279",
                new wikidata.explore.WikidataProperty(
                        "P279", "subclass of", "", "", "")));

        text(panel, "graph.edgeProperty").setText("P279");

        assertEquals("── subclass of (P279) out ──▶",
                named(panel, "graph.edgeLabel", JLabel.class).getText());
    }

    @Test void aStoredGraphHasAnExplicitExecutionSeparateFromGeneration() {
        GeneratedProjectModel model = model();
        GraphConstraintsPanel panel = graphPanel(model);
        panel.refresh();

        assertNotNull(button(panel, "Run graph"));
        assertTrue(!button(panel, "Run graph").isEnabled(),
                "a graph cannot run before it is saved and a runner is available");

        text(panel, "graph.edgeProperty").setText("P279");
        button(panel, "Apply graph").doClick();

        assertNotNull(graph(model));
        JLabel applied = named(panel, "graph.status", JLabel.class);
        assertTrue(applied.getText().contains("Run graph previews the output class"),
                applied.getText());

        panel.refresh();

        assertTrue(named(panel, "graph.status", JLabel.class).getText()
                        .contains("Run graph previews the output class"),
                "a graph read back from the model says it too");
    }

    /**
     * The graph is named by the class that declares it, and nothing else names it.
     *
     * <p>It used to carry a name of its own, which was at once the identity of its
     * annotation set — the result file is keyed by it — and a free-text field an editor
     * rewrote, so a run saved as PositionFilter came to sit beside a model calling
     * itself GraphConstraint with nothing able to notice.
     */
    @Test void theGraphIsNamedByTheClassThatDeclaresIt() {
        GeneratedProjectModel model = model();
        GraphConstraintsPanel panel = graphPanel(model);
        text(panel, "graph.edgeProperty").setText("P279");

        button(panel, "Apply graph").doClick();

        GeneratedClassModel declaring = model.findClass("PositionGraph");
        assertEquals("PositionGraph",
                declaring.graphSource().configurationFor(declaring.className()).name());
        assertNotNull(find(panel, ClassHeaderEditor.class),
                "the class's own name editor is where a graph is named");
    }

    @Test void aCompletedGraphProducesOneOutputClassForLaterApplication() {
        GeneratedProjectModel model = model();
        model.name("Historical Positions");
        GraphConstraintsPanel panel = graphPanel(model);
        EntityRef accepted = EntityRef.wikidata("Q1");
        EntityRef review = EntityRef.wikidata("Q2");
        EntityRef rejected = EntityRef.wikidata("Q3");
        GraphEvidenceCondition evidence = new GraphEvidenceCondition(
                "Position evidence",
                List.of(GraphPath.direct(new GraphRelation("wikidata", "P1001"),
                        GraphTraversalDirection.OUTGOING)),
                List.of(new GraphRelationExists(
                        new GraphRelation("wikidata", "P17"),
                        GraphTraversalDirection.OUTGOING)),
                GraphEvidenceCondition.ReviewDisposition.INCLUDE_AND_REPORT);
        GraphDiscoveryConfiguration.NextNode configuration =
                new GraphDiscoveryConfiguration.NextNode(
                        new GraphRelation("wikidata", "P31"),
                        GraphTraversalDirection.INCOMING,
                        GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION,
                        "Position", evidence);
        List<GraphEvidenceConditionResult> classifications = List.of(
                classified(accepted, GraphEvidenceConditionResult.Decision.ACCEPTED),
                classified(review, GraphEvidenceConditionResult.Decision.REVIEW),
                classified(rejected, GraphEvidenceConditionResult.Decision.REJECTED));
        GraphDiscoveryExecutor.NodeResult node = new GraphDiscoveryExecutor.NodeResult(
                1, configuration, null, List.of(accepted, review, rejected),
                List.of(accepted), List.of(rejected), List.of(review), classifications,
                List.of(), List.of(), List.of());
        ConfiguredGraphDiscoveryQuery.Result result =
                new ConfiguredGraphDiscoveryQuery.Result(
                        new GraphDiscoveryExecutor.Result(List.of(), List.of(node)),
                        java.util.Map.of(), 0);

        ProcessWorkflowResults<GraphDiscoveryResultStore.Artifact> results =
                panel.graphResults(result, "PositionValidity");
        GraphDiscoveryResultStore.Artifact artifact = results.resultDecision().get();

        assertEquals("Position", artifact.outputClass());
        assertEquals(List.of("Q1", "Q2"), artifact.acceptedCandidates().stream()
                        .map(wikidata.explore.extract.WikidataDynamicObject::getIdentifier).toList(),
                "Review is included because the authored policy says INCLUDE_AND_REPORT");
        assertEquals("Apply result", results.applyVerb());
        assertNull(button(panel, "Create population selection…"));
        assertEquals("data/wikidata/historicalpositions/positionvalidity.graph.snapshot.json",
                GraphDiscoveryResultStore.destination(
                        "Historical Positions", "PositionValidity").getPath());
    }

    @Test void graphAnnotationsReferenceCandidatesAndManualDecisionsOverrideInclusion() {
        EntityRef accepted = EntityRef.wikidata("Q1");
        EntityRef rejected = EntityRef.wikidata("Q2");
        GraphDiscoveryConfiguration.NextNode output = new GraphDiscoveryConfiguration.NextNode(
                new GraphRelation("wikidata", "P31"), GraphTraversalDirection.OUTGOING,
                GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION, "Position", null);
        GraphDiscoveryExecutor.NodeResult node = new GraphDiscoveryExecutor.NodeResult(
                1, output, null, List.of(accepted, rejected), List.of(accepted),
                List.of(rejected), List.of(), List.of(), List.of(), List.of(), List.of());
        ConfiguredGraphDiscoveryQuery.Result result = new ConfiguredGraphDiscoveryQuery.Result(
                new GraphDiscoveryExecutor.Result(List.of(), List.of(node)),
                java.util.Map.of("Q1", "One", "Q2", "Two"), 2);

        GraphDiscoveryResultStore.Artifact artifact =
                GraphDiscoveryResultStore.artifact("Project", "PositionValidity", result);
        var firstAnnotation = artifact.instances().getFirst();
        var firstCandidate = artifact.candidates().getFirst();
        assertTrue(firstAnnotation.get(GraphDiscoveryResultStore.ANNOTATED_INSTANCE)
                == firstCandidate);
        assertTrue(firstCandidate.get(GraphDiscoveryResultStore.GRAPH_ANNOTATION)
                == firstAnnotation);
        assertEquals(List.of("Q1"), artifact.acceptedCandidates().stream()
                .map(wikidata.explore.extract.WikidataDynamicObject::qid).toList());

        GraphDiscoveryResultStore.manualDecision(artifact.instances().get(1), "Accepted");
        GraphDiscoveryResultStore.manualDecision(firstAnnotation, "Rejected");
        assertEquals(List.of("Q2"), artifact.acceptedCandidates().stream()
                .map(wikidata.explore.extract.WikidataDynamicObject::qid).toList());

        ProcessWorkflowResults<GraphDiscoveryResultStore.Artifact> shown =
                graphPanel(model()).graphResults(result, "PositionValidity");
        assertEquals(List.of("All — 2 total", "Accepted — 1 total",
                        "Review — 0 total", "Rejected — 1 total"),
                shown.tabs().stream().map(ProcessWorkflowResults.Tab::title).toList());
        assertTrue(shown.tabs().stream().allMatch(tab -> tab.selectionActions().size() == 3));
    }

    @Test void modelKindDoesNotDisableAReadyGraphConstraint() {
        GeneratedProjectModel model = model();
        model.projectKind(GeneratedProjectModel.ProjectKind.MODEL);
        graphClass(model, "PositionGraph").graphSource(source("P279"));
        GraphConstraintsPanel panel = graphPanel(model);
        wikidata.explore.extract.WikidataDynamicObject position =
                new wikidata.explore.extract.WikidataDynamicObject("Q4164871", "Position");
        position.type("Position");
        panel.loadedInstances(() -> List.of(position));
        panel.setProcessRunner(new SwingProcessRunner(null, null, null));
        panel.refresh();

        assertTrue(button(panel, "Run graph").isEnabled(),
                "graph readiness is determined by the graph and its input, not project kind");
    }

    @Test void graphDiscoveryUsesTheSharedPlanRunningResultsWorkflow()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/wikidata/explore/workbench/GraphConstraintsPanel.java"));

        assertTrue(source.contains("SwingProcessWorkflow.start(this, runner, action)"),
                "Run graph must open the shared process workflow");
        assertTrue(source.contains("new ProcessWorkflowPlan("),
                "the graph must show its scope before execution");
        assertTrue(source.contains("ProcessWorkflowPlan.Tab.component("),
                "the plan must show the configured graph as a diagram");
        assertTrue(source.contains("loadedInstances.get()), true"),
                "the diagram, not the generic pipeline card, must open first");
        assertTrue(source.contains(".withoutPipelineTab()"),
                "the one-step pipeline repeats the graph plan and must stay hidden");
        assertTrue(source.contains("new ProcessWorkflowResults<>("),
                "the graph results must remain in the same workflow");
        assertTrue(source.contains("return WikidataLinks.valueLinker()"),
                "QIDs in the shared workflow must retain their Wikidata links");
        assertTrue(!source.contains("runner.wireButton(run"),
                "the legacy direct-query path must not remain as a second execution path");
    }

    @Test void executionPlanDrawsTraversalEvidenceAndTestEdges() throws Exception {
        GeneratedProjectModel model = model();
        model.rootClass().seedQids().add("Q4164871");
        GraphConstraintsPanel panel = graphPanel(model);
        text(panel, "graph.edgeProperty").setText("P279");
        text(panel, "graph.evidenceProperty").setText("P1001");
        button(panel, "Add evidence relation").doClick();
        text(panel, "graph.testProperty").setText("P576");
        button(panel, "Add evidence test").doClick();
        button(panel, "Apply graph").doClick();

        GraphViewModel graph = GraphConstraintsPanel.graphPlanModel(model, model.findClass("PositionGraph"));

        assertEquals(List.of("Position", "Position", "Evidence entity",
                        "Has a value"),
                graph.nodes().stream().map(GraphViewModel.Node::label).toList());
        assertEquals(List.of("P279 out", "P1001 out", "P576 out"),
                graph.edges().stream().map(GraphViewModel.Edge::label).toList());

        GraphDiscoveryPlanDiagram diagram = new GraphDiscoveryPlanDiagram(graph,
                "Undecidable nodes continue and are reported in Review.");
        diagram.setSize(diagram.getPreferredSize());
        BufferedImage image = new BufferedImage(diagram.getWidth(), diagram.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        diagram.printAll(graphics);
        graphics.dispose();
        Path artifact = Path.of("target/ui-artifacts/graph-execution-plan.png");
        Files.createDirectories(artifact.getParent());
        ImageIO.write(image, "png", artifact.toFile());
    }

    @Test void theRunPlanSeparatesLoadedFactsFromRepeatedWork() throws Exception {
        GeneratedProjectModel model = model();
        model.name("Test");
        model.rootClass().seedQids().add("Q4164871");
        GraphConstraintsPanel panel = graphPanel(model);
        text(panel, "graph.edgeProperty").setText("P31");
        button(panel, "Apply graph").doClick();

        DynamicViewable summary = GraphConstraintsPanel.graphSummary(model, model.findClass("PositionGraph"));

        assertEquals("Load saved answers from the persistent graph cache; download only missing answers",
                summary.get("Adjacency facts"));
        assertEquals("Repeat locally from the loaded graph facts",
                summary.get("Classification"));
        assertEquals("Fetch again; labels are not stored in the graph cache",
                summary.get("Labels"));
        assertEquals("Rebuild and save every start and reached entity for TransformApp",
                summary.get("Results"));
        assertEquals("data/wikidata/test/positiongraph.graph.snapshot.json",
                summary.get("Results file"),
                "the annotation set is keyed by the class name, so a rename moves it");
        assertEquals("PositionGraph", summary.get("Annotation set"));
        renderArtifact(new Card(summary,
                        ViewConfig.all(DynamicViewable.class), false),
                "graph-run-loaded-and-repeated-work.png");
    }

    @Test void resultTabsGiveEveryEntityToObjectviewsVirtualizedRenderer() {
        List<EntityRef> entities = java.util.stream.IntStream.rangeClosed(1, 1_001)
                .mapToObj(value -> EntityRef.wikidata("Q" + value)).toList();
        ConfiguredGraphDiscoveryQuery.Result result =
                new ConfiguredGraphDiscoveryQuery.Result(
                        new GraphDiscoveryExecutor.Result(List.of(), List.of()),
                        java.util.Map.of(), 0);

        ProcessWorkflowResults.Tab<Void> tab = GraphConstraintsPanel.resultTab(
                "Accepted", entities, result, "Position");

        assertEquals("Accepted — 1001 total", tab.title());
        assertEquals(1_001, tab.cards().size(),
                "objectview virtualizes the complete result; this producer must not truncate it");
    }

    @Test void completeGraphResultUsesTheOrdinaryTransformappDomainWriter()
            throws Exception {
        EntityRef start = EntityRef.wikidata("Q1");
        EntityRef accepted = EntityRef.wikidata("Q2");
        EntityRef rejected = EntityRef.wikidata("Q3");
        GraphDiscoveryExecutor.NodeResult node = new GraphDiscoveryExecutor.NodeResult(
                1, outputNode("Position"), new datasource.graph.GraphTraversalStep(
                        "step", "Start", "Position", "Graph",
                        new GraphRelation("wikidata", "P31"),
                        GraphTraversalDirection.OUTGOING,
                        datasource.graph.GraphExpansionPolicy.CURATED),
                List.of(accepted, rejected), List.of(accepted), List.of(rejected),
                List.of(), List.of(rejected(rejected, "Kind", "No kind matched")),
                List.of(), List.of(), List.of());
        ConfiguredGraphDiscoveryQuery.Result result =
                new ConfiguredGraphDiscoveryQuery.Result(
                        new GraphDiscoveryExecutor.Result(List.of(start), List.of(node)),
                        java.util.Map.of("Q1", "Root", "Q2", "Kept", "Q3", "Dropped"), 3);
        java.util.concurrent.atomic.AtomicReference<String> savedName =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<java.util.Collection<? extends objectview.Viewable>>
                savedMembers = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<domain.DomainModel> savedDomain =
                new java.util.concurrent.atomic.AtomicReference<>();

        GraphDiscoveryResultStore.save("History", result, (name, members, schema) -> {
            savedName.set(name);
            savedMembers.set(members);
            savedDomain.set(schema);
            return "saved";
        });

        assertEquals("History", savedName.get());
        assertEquals(2, savedMembers.get().size(),
                "the output candidates are persisted as annotations");
        assertEquals(List.of("History"),
                savedDomain.get().servedTypes());
        assertEquals(List.of("Q2", "Q3"), savedMembers.get().stream()
                .map(wikidata.explore.extract.WikidataDynamicObject.class::cast)
                .map(wikidata.explore.extract.WikidataDynamicObject::getIdentifier).toList(),
                "saved graph members use their ordinary Wikidata identity");
        assertTrue(savedMembers.get().stream()
                .map(wikidata.explore.extract.WikidataDynamicObject.class::cast)
                .map(objectview.field.FieldSet::of)
                .allMatch(fields -> fields.read("wikidataSource")
                        instanceof java.util.List<?> sources && sources.size() == 1),
                "every saved graph member has the normal Wikidata source field");
        objectview.field.FieldRef savedSource = savedDomain.get()
                .fieldSchema("History").fields().stream()
                .filter(field -> "wikidataSource".equals(field.name()))
                .findFirst().orElseThrow();
        assertEquals(objectview.field.FieldRole.PROVENANCE, savedSource.role(),
                "the artificial class model, not only its instances, declares the source");
        assertTrue(savedSource.reference());
        assertEquals(List.of("Accepted", "Review", "Rejected"),
                savedDomain.get().valueSelection("History",
                        objectview.field.FieldPath.parse("Graph decision")).values());
        List<String> decisions = savedMembers.get().stream()
                .map(wikidata.explore.extract.WikidataDynamicObject.class::cast)
                .map(value -> String.valueOf(value.get("Graph decision"))).toList();
        assertEquals(List.of("Accepted", "Rejected"), decisions);
    }

    @Test void graphResultsAreAppliedToTheProjectAndSavedOnlyWithTheProject()
            throws Exception {
        EntityRef start = EntityRef.wikidata("Q1");
        GraphDiscoveryConfiguration.NextNode output = new GraphDiscoveryConfiguration.NextNode(
                new GraphRelation("wikidata", "P31"), GraphTraversalDirection.OUTGOING,
                GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION, "Position", null);
        GraphDiscoveryExecutor.NodeResult node = new GraphDiscoveryExecutor.NodeResult(
                1, output, null, List.of(start), List.of(start), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        ConfiguredGraphDiscoveryQuery.Result result =
                new ConfiguredGraphDiscoveryQuery.Result(
                        new GraphDiscoveryExecutor.Result(List.of(), List.of(node)),
                        java.util.Map.of("Q1", "Root"), 1);
        GraphDiscoveryResultStore.Artifact artifact =
                GraphDiscoveryResultStore.artifact(
                        "Historical Positions", "History", result);

        assertEquals(List.of("History"), artifact.model().servedTypes());
        assertEquals("Root", artifact.instances().getFirst().getDisplayName());
        assertEquals("Q1", artifact.instances().getFirst().getIdentifier());
        assertNotNull(artifact.model().fieldSchema("History")
                .field("wikidataSource"));
        ProcessWorkflowResults.Tab<GraphDiscoveryResultStore.Artifact> preview =
                GraphConstraintsPanel.artifactTab("All", artifact, null);
        assertTrue(preview.cards().getFirst().view() == artifact.instances().getFirst(),
                "the preview must render the exact instance later passed to Save result");
        assertNotNull(objectview.field.FieldSet.of(preview.shapeSample(),
                        artifact.model().fieldSchema("History"))
                .field("wikidataSource"),
                "the preview must use the artificial model saved with those instances");
        assertEquals("Save graph annotations \"History\" for \"Historical Positions\" "
                        + "with 1 instance and their field model to data/wikidata/"
                        + "historicalpositions/history.graph.snapshot.json.",
                GraphConstraintsPanel.saveDescription(artifact));
        GeneratedProjectModel owner = model();
        owner.name("Historical Positions");
        ProcessWorkflowResults<GraphDiscoveryResultStore.Artifact> results =
                graphPanel(owner).graphResults(result, "History");
        assertEquals("Apply result", results.applyVerb());
        assertTrue(results.resultConfirmation().isBlank(),
                "graph results have no separate persistence action");
    }

    @Test void rejectedNodesAreGroupedByTheirRecordedRejectionReason()
            throws Exception {
        EntityRef first = EntityRef.wikidata("Q1");
        EntityRef second = EntityRef.wikidata("Q2");
        EntityRef third = EntityRef.wikidata("Q3");
        List<GraphEvidenceConditionResult> classifications = List.of(
                rejected(first, "Jurisdiction kind", "No configured kind matched"),
                rejected(second, "Jurisdiction kind", "No configured kind matched"),
                rejected(third, "Entity kind", "The entity is a list"));
        GraphDiscoveryExecutor.NodeResult node = new GraphDiscoveryExecutor.NodeResult(
                1, outputNode("Position"), null, List.of(first, second, third), List.of(),
                List.of(first, second, third), List.of(), classifications,
                List.of(), List.of(), List.of());
        ConfiguredGraphDiscoveryQuery.Result result =
                new ConfiguredGraphDiscoveryQuery.Result(
                        new GraphDiscoveryExecutor.Result(List.of(), List.of(node)),
                        java.util.Map.of("Q1", "First", "Q2", "Second", "Q3", "Third"),
                        3);

        ProcessWorkflowResults.Tab<Void> tab =
                GraphConstraintsPanel.rejectedResultTab(node, result);

        assertEquals("Rejected — 3 total", tab.title());
        assertEquals(2, tab.cards().size());
        DynamicViewable unmatched = (DynamicViewable) tab.cards().getFirst().view();
        assertEquals("No configured kind matched", unmatched.getDisplayName());
        assertEquals("Jurisdiction kind", unmatched.get("Condition"));
        assertEquals(2, unmatched.get("Count"));
        @SuppressWarnings("unchecked")
        List<DynamicViewable> members =
                (List<DynamicViewable>) unmatched.get("Rejected nodes");
        assertEquals(List.of("First", "Second"),
                members.stream().map(DynamicViewable::getDisplayName).toList());

        renderArtifact(new Card(unmatched,
                        ViewConfig.all(DynamicViewable.class), false),
                "rejected-nodes-grouped-by-reason.png");
    }

    private static GraphEvidenceConditionResult rejected(
            EntityRef node, String condition, String reason) {
        return new GraphEvidenceConditionResult(
                GraphEvidenceConditionResult.Decision.REJECTED, node, condition,
                GraphEvidenceCondition.ReviewDisposition.INCLUDE_AND_REPORT,
                List.of(), List.of(), List.of(), List.of(), reason);
    }

    private static GraphEvidenceConditionResult classified(
            EntityRef node, GraphEvidenceConditionResult.Decision decision) {
        return new GraphEvidenceConditionResult(
                decision, node, "Position evidence",
                GraphEvidenceCondition.ReviewDisposition.INCLUDE_AND_REPORT,
                List.of(), List.of(), List.of(), List.of(), decision.name());
    }

    @Test void aGraphThatCannotStartExplainsTheConfigurationFailureInADialog() {
        GeneratedProjectModel model = model();
        GraphConstraintsPanel panel = graphPanel(model);
        text(panel, "graph.edgeProperty").setText("P31");
        button(panel, "Apply graph").doClick();

        List<String> dialog = new ArrayList<>();
        panel.errorDialog((title, message) -> {
            dialog.add(title);
            dialog.add(message);
        });
        panel.setProcessRunner(new SwingProcessRunner(null, null, null));

        button(panel, "Run graph").doClick();

        assertEquals(List.of("Graph discovery failed",
                "No loaded Position instance has a Wikidata source QID"), dialog);
        assertTrue(named(panel, "graph.status", JLabel.class).getText()
                .contains("No loaded Position instance"));
    }

    @Test void evidenceRequiresBothTheRelationAndItsTest() {
        GeneratedProjectModel model = model();
        GraphConstraintsPanel panel = graphPanel(model);
        panel.refresh();
        text(panel, "graph.edgeProperty").setText("P279");
        text(panel, "graph.evidenceProperty").setText("P1001");
        button(panel, "Add evidence relation").doClick();

        button(panel, "Apply graph").doClick();

        assertNull(graph(model));
        assertTrue(named(panel, "graph.status", JLabel.class).getText()
                .contains("both an evidence relation and an evidence test"));
    }

    @Test void clearingTheDraftNeverRemovesTheSavedGraph() {
        // Every other control here builds a draft, and the panel says so. Clearing the
        // draft touches the controls only; removing the graph is a separate, named act.
        GeneratedProjectModel model = model();
        GraphConstraintsPanel panel = graphPanel(model);
        panel.refresh();
        text(panel, "graph.edgeProperty").setText("P279");
        button(panel, "Apply graph").doClick();
        assertNotNull(graph(model));

        button(panel, "Clear draft").doClick();
        button(panel, "Apply graph").doClick();

        assertNotNull(graph(model),
                "neither clearing the draft nor re-applying it may delete the graph");
    }

    /**
     * A graph constraint is removed where every class is removed.
     *
     * <p>This editor had a Remove of its own, because the graph was the project's single
     * configuration rather than a class. A second, kind-specific delete beside the one
     * that already exists is exactly the exception a general construct is meant to
     * retire.
     */
    @Test void removingAGraphClassIsRemovingAClass() {
        GeneratedProjectModel model = model();
        GraphConstraintsPanel panel = graphPanel(model);
        text(panel, "graph.edgeProperty").setText("P279");
        button(panel, "Apply graph").doClick();
        assertNotNull(graph(model));

        assertNull(button(panel, "Remove discovery graph"),
                "the graph editor offers no delete of its own");

        model.removeClass(model.findClass("PositionGraph"));

        assertNull(model.findClass("PositionGraph"));
        assertTrue(model.graphClasses().isEmpty());
    }

    @Test void graphConstraintsEditedAndThenLeftAreStillSavedWithTheDomain() {
        GeneratedProjectModel model = model();
        model.rootClass().seedQids().add("Q4164871");
        graphClass(model, "PositionGraph");
        ModelSourceWorkbenchPanel workbench = new ModelSourceWorkbenchPanel(model);
        workbench.edit(model.findClass("PositionGraph"));
        GraphConstraintsPanel panel = find(workbench, GraphConstraintsPanel.class);
        text(panel, "graph.edgeProperty").setText("P279");

        // Clicking any other node is what used to lose the draft: the graph editor was
        // flushed only while its own section was still selected.
        workbench.edit(model.rootClass());
        workbench.applyEdits();

        assertNotNull(graph(model),
                "constraints left by selecting another node must still reach the model");
        assertEquals("P279", graph(model).nextNodes()
                .getFirst().property().relationId());
    }

    @Test void choosingOnlyAStartClassSavesItAndSaysWhatIsStillMissing() {
        // Reported: the start class was changed to PositionDiscoveryStart, Apply was
        // pressed, the domain saved and ModelBuilder restarted — and the section still
        // showed Position. Apply had taken the emptied-draft branch, which is the REMOVE
        // gesture, and reported "No discovery graph configured" while throwing the choice
        // away. The combo always has a selection, so a start class can never be the blank
        // that makes a draft empty; the missing edge is.
        GeneratedProjectModel model = model();
        GeneratedClassModel start = new GeneratedClassModel("PositionDiscoveryStart");
        start.seedQids().add("Q4164871");
        model.addClass(start);
        GraphConstraintsPanel panel = graphPanel(model);
        panel.refresh();

        selectClass(panel, "PositionDiscoveryStart");
        button(panel, "Apply graph").doClick();

        assertNotNull(graph(model),
                "the start class is a decision of its own and must be kept");
        assertEquals("PositionDiscoveryStart", graph(model)
                .startNode().qidSourceClass());
        assertTrue(graph(model).nextNodes().isEmpty(),
                "no edge was given, so the graph has no traversal yet");
        assertTrue(status(panel).contains("Add the property connecting the two nodes"),
                "Apply must still say what the graph needs, was: " + status(panel));
        assertFalse(button(panel, "Run graph").isEnabled(),
                "a graph with no edge traverses nothing and cannot run");
    }

    @Test void anEmptiedDraftIsIncompleteRatherThanADeletion() {
        // The other side of the same branch. A blank edge cannot distinguish "delete
        // this" from "not filled in yet", so it no longer tries to: applying it keeps
        // the start node and drops only the edge, and the graph itself is removed only
        // by the button that says so.
        GeneratedProjectModel model = model();
        model.rootClass().seedQids().add("Q4164871");
        graphClass(model, "PositionGraph").graphSource(source("P279"));
        GraphConstraintsPanel panel = graphPanel(model);
        panel.refresh();

        text(panel, "graph.edgeProperty").setText("");
        button(panel, "Apply graph").doClick();

        assertNotNull(graph(model),
                "an emptied draft must not delete the graph behind it");
        assertTrue(graph(model).nextNodes().isEmpty());
        assertTrue(status(panel).contains("Add the property"), status(panel));
    }

    @Test void loadingADomainInPlaceKeepsItsGraphThroughTheNextSave() {
        // Loading reuses the model INSTANCE: the frame detaches the editors, copies the
        // new domain's contents in, and the next save flushes whatever the panels hold.
        // An emptied draft is how a graph is REMOVED, so a graph editor still showing the
        // previous domain's blank draft would delete the graph the load just brought in.
        GeneratedProjectModel model = model();
        ModelSourceWorkbenchPanel workbench = new ModelSourceWorkbenchPanel(model);

        GeneratedProjectModel loaded = model();
        loaded.rootClass().seedQids().add("Q4164871");
        graphClass(loaded, "PositionGraph").graphSource(source("P279"));

        workbench.abandonEdits();
        model.copyContentsFrom(loaded);
        workbench.applyEdits();

        assertNotNull(graph(model),
                "the loaded domain's graph must survive the first save after loading");
        assertEquals("P279", graph(model).nextNodes()
                .getFirst().property().relationId());
    }

    @Test void savingADomainWithNoGraphDoesNotInventOne() {
        // The start-class combo always carries a selection, so a flush that applied the
        // panel's default state would store a start node naming whichever class is
        // first — configuration the modeller never authored, written by a save.
        GeneratedProjectModel model = model();
        model.addClass(new GeneratedClassModel("PositionType"));
        graphClass(model, "PositionGraph");
        ModelSourceWorkbenchPanel workbench = new ModelSourceWorkbenchPanel(model);
        workbench.edit(model.findClass("PositionGraph"));

        workbench.edit(model.rootClass());
        workbench.applyEdits();

        assertNull(graph(model),
                "a save must not author a discovery graph nobody asked for");
    }

    @Test void aStartClassSurvivesSavingTheDomainAndReopeningTheSection() throws Exception {
        // Reported twice: pick PositionDiscoveryStart, Apply, save the domain, restart —
        // and the section shows Position again, the first class in the list.
        GeneratedProjectModel model = model();
        model.name("historicalpositions");
        model.addClass(new GeneratedClassModel("PositionType"));
        GeneratedClassModel start = new GeneratedClassModel("PositionDiscoveryStart");
        start.seedQids().add("Q4164871");
        model.addClass(start);

        graphClass(model, "PositionGraph");
        ModelSourceWorkbenchPanel workbench = new ModelSourceWorkbenchPanel(model);
        workbench.edit(model.findClass("PositionGraph"));
        GraphConstraintsPanel panel = find(workbench, GraphConstraintsPanel.class);
        selectClass(panel, "PositionDiscoveryStart");
        button(panel, "Apply graph").doClick();
        // Leaving the section and saving is the flush that used to lose the draft.
        workbench.edit(model.rootClass());
        workbench.applyEdits();

        java.io.File file = java.io.File.createTempFile("graph-start", ".model.json");
        file.deleteOnExit();
        new wikidata.explore.model.GeneratedProjectModelStore().save(
                wikidata.explore.generation.DomainSave.persistedModel(model), file);
        GeneratedProjectModel reloaded =
                new wikidata.explore.model.GeneratedProjectModelStore().load(file);

        assertEquals("PositionDiscoveryStart",
                graph(reloaded).startNode().qidSourceClass(),
                "the saved file must carry the chosen start class");

        ModelSourceWorkbenchPanel reopenedWorkbench = new ModelSourceWorkbenchPanel(reloaded);
        reopenedWorkbench.edit(reloaded.findClass("PositionGraph"));
        GraphConstraintsPanel reopened =
                find(reopenedWorkbench, GraphConstraintsPanel.class);

        assertTrue(named(reopened, "graph.startInput", JComboBox.class).getSelectedItem()
                        .toString().startsWith("Class: PositionDiscoveryStart"),
                "reopening the section must show the saved start class, not the first one");
    }

    private static String status(Container root) {
        return named(root, "graph.status", JLabel.class).getText();
    }

    private static void selectClass(Container root, String className) {
        JComboBox<?> box = named(root, "graph.startInput", JComboBox.class);
        for (int i = 0; i < box.getItemCount(); i++) {
            if (box.getItemAt(i).toString().startsWith("Class: " + className + " ·")) {
                box.setSelectedIndex(i);
                return;
            }
        }
        throw new AssertionError(className + " is not offered as a start class");
    }

    private static wikidata.explore.model.GraphClassSource source(String pid) {
        return new wikidata.explore.model.GraphClassSource(
                new GraphDiscoveryConfiguration.StartNode(
                        "Position", GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY),
                List.of(new GraphDiscoveryConfiguration.NextNode(
                        new GraphRelation("wikidata", pid),
                        GraphTraversalDirection.OUTGOING,
                        GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY, "", null)));
    }

    @Test void abandoningEditsStopsAGraphFollowingIntoTheNextDomain() {
        // The project model INSTANCE is reused when a domain is loaded in place, so a
        // panel still holding the previous domain's graph would flush it into the new one.
        GeneratedProjectModel model = model();
        model.rootClass().seedQids().add("Q4164871");
        graphClass(model, "PositionGraph");
        ModelSourceWorkbenchPanel workbench = new ModelSourceWorkbenchPanel(model);
        workbench.edit(model.findClass("PositionGraph"));
        text(find(workbench, GraphConstraintsPanel.class), "graph.edgeProperty")
                .setText("P279");

        workbench.abandonEdits();
        model.findClass("PositionGraph").graphSource(null);
        workbench.applyEdits();

        assertNull(graph(model),
                "an abandoned draft belongs to the domain that was closed, not the next one");
    }

    @Test void theSavedDomainIsNamedByWhatTheInstancesAreStampedWith() throws Exception {
        // The artifact carries the graph constraint's name and stamps every instance
        // with it. Passing a name to the write as well let the two come apart: rows
        // typed one way inside a domain called another, with nothing to object.
        EntityRef start = EntityRef.wikidata("Q1");
        EntityRef accepted = EntityRef.wikidata("Q2");
        GraphDiscoveryExecutor.NodeResult node = new GraphDiscoveryExecutor.NodeResult(
                1, outputNode("Position"), new datasource.graph.GraphTraversalStep(
                        "step", "Start", "Position", "Graph",
                        new GraphRelation("wikidata", "P31"),
                        GraphTraversalDirection.OUTGOING,
                        datasource.graph.GraphExpansionPolicy.CURATED),
                List.of(accepted), List.of(accepted), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
        ConfiguredGraphDiscoveryQuery.Result result =
                new ConfiguredGraphDiscoveryQuery.Result(
                        new GraphDiscoveryExecutor.Result(List.of(start), List.of(node)),
                        java.util.Map.of("Q1", "Root", "Q2", "Kept"), 2);
        GraphDiscoveryResultStore.Artifact artifact =
                GraphDiscoveryResultStore.artifact("PositionLineage", result);
        java.util.concurrent.atomic.AtomicReference<String> savedName =
                new java.util.concurrent.atomic.AtomicReference<>();

        GraphDiscoveryResultStore.save(artifact, (name, members, schema) -> {
            savedName.set(name);
            return "saved";
        });

        assertEquals("PositionLineage", artifact.type());
        assertEquals(artifact.type(), savedName.get(),
                "the write asks the artifact rather than being told a second time");
        assertEquals(List.of("PositionLineage"),
                artifact.instances().stream().map(
                        wikidata.explore.extract.WikidataDynamicObject::typeName)
                        .distinct().toList(),
                "and the instances carry that same name");
    }

    @Test void theRealSaveWritesBesideTheProjectAndRegistersNothing(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        // The write and the registry entry are what this method exists to produce, and a
        // writer stand-in exercised neither. The annotations must land beside the project
        // that produced them, and be listed for TransformApp WITHOUT being served: the
        // quiz web app serves every registered dataset, and a record of a classification
        // run — Step, Decision, witnesses — is not quiz content.
        EntityRef start = EntityRef.wikidata("Q1");
        EntityRef accepted = EntityRef.wikidata("Q2");
        GraphDiscoveryExecutor.NodeResult node = new GraphDiscoveryExecutor.NodeResult(
                1, outputNode("Position"), new datasource.graph.GraphTraversalStep(
                        "step", "Start", "Position", "Graph",
                        new GraphRelation("wikidata", "P31"),
                        GraphTraversalDirection.OUTGOING,
                        datasource.graph.GraphExpansionPolicy.CURATED),
                List.of(accepted), List.of(accepted), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
        ConfiguredGraphDiscoveryQuery.Result result =
                new ConfiguredGraphDiscoveryQuery.Result(
                        new GraphDiscoveryExecutor.Result(List.of(start), List.of(node)),
                        java.util.Map.of("Q1", "Root", "Q2", "Kept"), 2);
        GraphDiscoveryResultStore.Artifact artifact = GraphDiscoveryResultStore.artifact(
                "Historical Positions", "PositionValidity", result);
        java.io.File registryFile = root.resolve("datasets.json").toFile();

        GraphDiscoveryResultStore.save(artifact, dataset.DomainStorage.in(root.toFile()));

        java.io.File written = root.resolve("historicalpositions")
                .resolve("positionvalidity.graph.snapshot.json").toFile();
        assertTrue(written.isFile(),
                "the annotations live beside the project that produced them");
        assertTrue(java.nio.file.Files.readString(written.toPath())
                .contains("PositionValidity"),
                "and the instances carry the graph constraint's name");

        assertFalse(registryFile.isFile(),
                "the annotations are part of the project that produced them, not a "
                        + "dataset of their own: a row would list a second copy of what "
                        + "the project's instances already carry, under a second name");
    }

    /** A node that says it produces a class population, which is what makes it the
     *  graph's output. Omitting it is what made the store guess from a label. */
    private static datasource.graph.GraphDiscoveryConfiguration.NextNode outputNode(
            String populationClass) {
        return new datasource.graph.GraphDiscoveryConfiguration.NextNode(
                new GraphRelation("wikidata", "P31"),
                GraphTraversalDirection.OUTGOING,
                datasource.graph.GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION,
                populationClass, null);
    }

    private static ConfiguredGraphDiscoveryQuery.Result resultFor(String outputClass) {
        EntityRef start = EntityRef.wikidata("Q1");
        EntityRef accepted = EntityRef.wikidata("Q2");
        GraphDiscoveryExecutor.NodeResult node = new GraphDiscoveryExecutor.NodeResult(
                1, outputNode(outputClass), new datasource.graph.GraphTraversalStep(
                        "step", "Start", outputClass, "Graph",
                        new GraphRelation("wikidata", "P31"),
                        GraphTraversalDirection.OUTGOING,
                        datasource.graph.GraphExpansionPolicy.CURATED),
                List.of(accepted), List.of(accepted), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
        return new ConfiguredGraphDiscoveryQuery.Result(
                new GraphDiscoveryExecutor.Result(List.of(start), List.of(node)),
                java.util.Map.of("Q1", "Root", "Q2", "Kept"), 2);
    }

    /** The editor opens on a GRAPH class, the way every other kind editor does. */
    private static GraphConstraintsPanel graphPanel(GeneratedProjectModel model) {
        GeneratedClassModel graphClass = model.findClass("PositionGraph") == null
                ? graphClass(model, "PositionGraph") : model.findClass("PositionGraph");
        GraphConstraintsPanel panel = new GraphConstraintsPanel(model);
        panel.edit(graphClass);
        return panel;
    }

    private static GeneratedClassModel graphClass(
            GeneratedProjectModel model, String name) {
        GeneratedClassModel graphClass = model.findClass(name);
        if (graphClass == null) {
            graphClass = new GeneratedClassModel(name);
            model.addClass(graphClass);
        }
        graphClass.classKind(wikidata.explore.model.ClassKind.GRAPH);
        return graphClass;
    }

    /** The graph the project's one graph class declares. */
    private static wikidata.explore.model.GraphClassSource graph(
            GeneratedProjectModel model) {
        GeneratedClassModel graphClass = model.findClass("PositionGraph");
        return graphClass == null ? null : graphClass.graphSource();
    }

    private static GeneratedProjectModel model() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass(new GeneratedClassModel("Position"));
        return model;
    }

    private static JTextComponent text(Container root, String name) {
        return named(root, name, JTextComponent.class);
    }

    private static JButton button(Container root, String label) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && label.equals(button.getText())) return button;
            if (child instanceof Container nested) {
                JButton found = button(nested, label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends Component> T named(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container nested) {
                try { return named(nested, name, type); }
                catch (AssertionError ignored) { }
            }
        }
        throw new AssertionError("No " + type.getSimpleName() + " named " + name);
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) {
                T found = findOrNull(nested, type);
                if (found != null) return found;
            }
        }
        throw new AssertionError("No " + type.getSimpleName());
    }

    private static <T extends Component> T findOrNull(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) {
                T found = findOrNull(nested, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void layoutTree(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) {
            if (child instanceof Container nested) layoutTree(nested);
        }
    }

    private static void renderArtifact(Card card, String name) throws Exception {
        int height = Math.max(300, card.getPreferredSize().height);
        BufferedImage image = new BufferedImage(900, height,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        SwingUtilities.invokeAndWait(() -> {
            card.setSize(image.getWidth(), image.getHeight());
            layoutTree(card);
            card.printAll(graphics);
        });
        graphics.dispose();
        Path artifact = Path.of("target/ui-artifacts", name);
        Files.createDirectories(artifact.getParent());
        ImageIO.write(image, "png", artifact.toFile());
    }
}
