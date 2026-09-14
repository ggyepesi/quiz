package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import datasource.graph.GraphDiscoveryConfiguration;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.constraint.GraphEvidenceCondition;
import graphview.GraphViewModel;
import process.swing.SwingProcessRunner;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphConstraintsPanelTest {

    @Test void graphConstraintIsASeparateConfigurationSection() {
        GeneratedProjectModel model = model();
        ModelSourceWorkbenchPanel workbench = new ModelSourceWorkbenchPanel(model);

        workbench.edit(SingleRootClassModelPanel.ConfigurationSection.GRAPH_CONSTRAINTS);

        GraphConstraintsPanel panel = find(workbench, GraphConstraintsPanel.class);
        assertTrue(panel.isVisible());
        assertNotNull(button(panel, "Apply graph"));
        assertNull(find(workbench, FieldSourcePanel.class)
                .getClientProperty("graph constraints"),
                "the graph editor is not encoded in field configuration");
    }

    @Test void configuredQidsAreReusedButApplyingIsExplicit() {
        GeneratedProjectModel model = model();
        GeneratedClassModel position = model.rootClass();
        position.seedQids().add("Q4164871");
        GraphConstraintsPanel panel = new GraphConstraintsPanel(model);
        panel.refresh();

        assertEquals("1 QID", named(panel, "graph.startQids", JLabel.class).getText());

        text(panel, "graph.edgeProperty").setText("P279");
        named(panel, "graph.edgeDirection", JComboBox.class).setSelectedIndex(1);
        named(panel, "graph.targetUse", JComboBox.class).setSelectedIndex(1);
        text(panel, "graph.evidenceProperty").setText("P1001");
        button(panel, "Add evidence relation").doClick();
        text(panel, "graph.testProperty").setText("P576");
        button(panel, "Add evidence test").doClick();
        named(panel, "graph.reviewDisposition", JComboBox.class).setSelectedItem(
                GraphEvidenceCondition.ReviewDisposition.EXCLUDE_AND_REPORT);

        assertNull(model.graphDiscoveryConfiguration(),
                "editing the draft must not mutate the model");
        button(panel, "Apply graph").doClick();

        assertEquals("P279", model.graphDiscoveryConfiguration().nextNodes()
                .getFirst().property().relationId());
        GraphEvidenceCondition evidence = model.graphDiscoveryConfiguration().nextNodes()
                .getFirst().evidenceCondition();
        assertEquals("P1001", evidence.evidencePaths().getFirst().relation().relationId());
        assertEquals("P576", evidence.tests().getFirst().relation().relationId());
        assertEquals(GraphEvidenceCondition.ReviewDisposition.EXCLUDE_AND_REPORT,
                evidence.reviewDisposition());
    }

    @Test void aStoredGraphHasAnExplicitExecutionSeparateFromGeneration() {
        GeneratedProjectModel model = model();
        GraphConstraintsPanel panel = new GraphConstraintsPanel(model);
        panel.refresh();

        assertNotNull(button(panel, "Run graph"));
        assertTrue(!button(panel, "Run graph").isEnabled(),
                "a graph cannot run before it is saved and a runner is available");

        text(panel, "graph.edgeProperty").setText("P279");
        button(panel, "Apply graph").doClick();

        assertNotNull(model.graphDiscoveryConfiguration());
        JLabel applied = named(panel, "graph.status", JLabel.class);
        assertTrue(applied.getText().contains("Generation does not use it yet"),
                applied.getText());

        panel.refresh();

        assertTrue(named(panel, "graph.status", JLabel.class).getText()
                        .contains("Generation does not use it yet"),
                "a graph read back from the model says it too");
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
        GraphConstraintsPanel panel = new GraphConstraintsPanel(model);
        text(panel, "graph.edgeProperty").setText("P279");
        text(panel, "graph.evidenceProperty").setText("P1001");
        button(panel, "Add evidence relation").doClick();
        text(panel, "graph.testProperty").setText("P576");
        button(panel, "Add evidence test").doClick();
        button(panel, "Apply graph").doClick();

        GraphViewModel graph = GraphConstraintsPanel.graphPlanModel(model);

        assertEquals(List.of("Position", "Intermediate node", "Evidence entity",
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

    @Test void theRunPlanSaysDownloadedFactsWillBeReused() {
        GeneratedProjectModel model = model();
        model.rootClass().seedQids().add("Q4164871");
        GraphConstraintsPanel panel = new GraphConstraintsPanel(model);
        text(panel, "graph.edgeProperty").setText("P31");
        button(panel, "Apply graph").doClick();

        Object policy = GraphConstraintsPanel.graphSummary(model)
                .get("Downloaded facts");

        assertEquals("Reuse the persistent local cache; fetch only missing adjacency",
                policy);
    }

    @Test void aGraphThatCannotStartExplainsTheConfigurationFailureInADialog() {
        GeneratedProjectModel model = model();
        GraphConstraintsPanel panel = new GraphConstraintsPanel(model);
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
                "The start class 'Position' has no configured QIDs"), dialog);
        assertTrue(named(panel, "graph.status", JLabel.class).getText()
                .contains("has no configured QIDs"));
    }

    @Test void evidenceRequiresBothTheRelationAndItsTest() {
        GeneratedProjectModel model = model();
        GraphConstraintsPanel panel = new GraphConstraintsPanel(model);
        panel.refresh();
        text(panel, "graph.edgeProperty").setText("P279");
        text(panel, "graph.evidenceProperty").setText("P1001");
        button(panel, "Add evidence relation").doClick();

        button(panel, "Apply graph").doClick();

        assertNull(model.graphDiscoveryConfiguration());
        assertTrue(named(panel, "graph.status", JLabel.class).getText()
                .contains("both an evidence relation and an evidence test"));
    }

    @Test void clearingTheDraftNeverRemovesTheSavedGraph() {
        // Every other control here builds a draft, and the panel says so. Clearing the
        // draft touches the controls only; removing the graph is a separate, named act.
        GeneratedProjectModel model = model();
        GraphConstraintsPanel panel = new GraphConstraintsPanel(model);
        panel.refresh();
        text(panel, "graph.edgeProperty").setText("P279");
        button(panel, "Apply graph").doClick();
        assertNotNull(model.graphDiscoveryConfiguration());

        button(panel, "Clear draft").doClick();
        button(panel, "Apply graph").doClick();

        assertNotNull(model.graphDiscoveryConfiguration(),
                "neither clearing the draft nor re-applying it may delete the graph");
    }

    @Test void removingTheGraphIsItsOwnNamedAction() {
        GeneratedProjectModel model = model();
        GraphConstraintsPanel panel = new GraphConstraintsPanel(model);
        panel.refresh();
        assertFalse(button(panel, "Remove discovery graph").isEnabled(),
                "nothing saved, nothing to remove");

        text(panel, "graph.edgeProperty").setText("P279");
        button(panel, "Apply graph").doClick();
        assertTrue(button(panel, "Remove discovery graph").isEnabled());

        button(panel, "Remove discovery graph").doClick();

        assertNull(model.graphDiscoveryConfiguration());
        assertFalse(button(panel, "Remove discovery graph").isEnabled());
        assertTrue(status(panel).contains("removed"), status(panel));
    }

    @Test void graphConstraintsEditedAndThenLeftAreStillSavedWithTheDomain() {
        GeneratedProjectModel model = model();
        model.rootClass().seedQids().add("Q4164871");
        ModelSourceWorkbenchPanel workbench = new ModelSourceWorkbenchPanel(model);
        workbench.edit(SingleRootClassModelPanel.ConfigurationSection.GRAPH_CONSTRAINTS);
        GraphConstraintsPanel panel = find(workbench, GraphConstraintsPanel.class);
        text(panel, "graph.edgeProperty").setText("P279");

        // Clicking any other node is what used to lose the draft: the graph editor was
        // flushed only while its own section was still selected.
        workbench.edit(model.rootClass());
        workbench.applyEdits();

        assertNotNull(model.graphDiscoveryConfiguration(),
                "constraints left by selecting another node must still reach the model");
        assertEquals("P279", model.graphDiscoveryConfiguration().nextNodes()
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
        GraphConstraintsPanel panel = new GraphConstraintsPanel(model);
        panel.refresh();

        selectClass(panel, "PositionDiscoveryStart");
        button(panel, "Apply graph").doClick();

        assertNotNull(model.graphDiscoveryConfiguration(),
                "the start class is a decision of its own and must be kept");
        assertEquals("PositionDiscoveryStart", model.graphDiscoveryConfiguration()
                .startNode().qidSourceClass());
        assertTrue(model.graphDiscoveryConfiguration().nextNodes().isEmpty(),
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
        model.graphDiscoveryConfiguration(graph("P279"));
        GraphConstraintsPanel panel = new GraphConstraintsPanel(model);
        panel.refresh();

        text(panel, "graph.edgeProperty").setText("");
        button(panel, "Apply graph").doClick();

        assertNotNull(model.graphDiscoveryConfiguration(),
                "an emptied draft must not delete the graph behind it");
        assertTrue(model.graphDiscoveryConfiguration().nextNodes().isEmpty());
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
        loaded.graphDiscoveryConfiguration(graph("P279"));

        workbench.abandonEdits();
        model.copyContentsFrom(loaded);
        workbench.applyEdits();

        assertNotNull(model.graphDiscoveryConfiguration(),
                "the loaded domain's graph must survive the first save after loading");
        assertEquals("P279", model.graphDiscoveryConfiguration().nextNodes()
                .getFirst().property().relationId());
    }

    @Test void savingADomainWithNoGraphDoesNotInventOne() {
        // The start-class combo always carries a selection, so a flush that applied the
        // panel's default state would store a start node naming whichever class is
        // first — configuration the modeller never authored, written by a save.
        GeneratedProjectModel model = model();
        model.addClass(new GeneratedClassModel("PositionType"));
        ModelSourceWorkbenchPanel workbench = new ModelSourceWorkbenchPanel(model);
        workbench.edit(SingleRootClassModelPanel.ConfigurationSection.GRAPH_CONSTRAINTS);

        workbench.edit(model.rootClass());
        workbench.applyEdits();

        assertNull(model.graphDiscoveryConfiguration(),
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

        ModelSourceWorkbenchPanel workbench = new ModelSourceWorkbenchPanel(model);
        workbench.edit(SingleRootClassModelPanel.ConfigurationSection.GRAPH_CONSTRAINTS);
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
                reloaded.graphDiscoveryConfiguration().startNode().qidSourceClass(),
                "the saved file must carry the chosen start class");

        ModelSourceWorkbenchPanel reopenedWorkbench = new ModelSourceWorkbenchPanel(reloaded);
        reopenedWorkbench.edit(SingleRootClassModelPanel.ConfigurationSection.GRAPH_CONSTRAINTS);
        GraphConstraintsPanel reopened =
                find(reopenedWorkbench, GraphConstraintsPanel.class);

        assertEquals("PositionDiscoveryStart", ((GeneratedClassModel) named(
                        reopened, "graph.startClass", JComboBox.class).getSelectedItem())
                        .className(),
                "reopening the section must show the saved start class, not the first one");
    }

    private static String status(Container root) {
        return named(root, "graph.status", JLabel.class).getText();
    }

    @SuppressWarnings("unchecked")
    private static void selectClass(Container root, String className) {
        JComboBox<GeneratedClassModel> box =
                named(root, "graph.startClass", JComboBox.class);
        for (int i = 0; i < box.getItemCount(); i++) {
            if (className.equals(box.getItemAt(i).className())) {
                box.setSelectedIndex(i);
                return;
            }
        }
        throw new AssertionError(className + " is not offered as a start class");
    }

    private static GraphDiscoveryConfiguration graph(String pid) {
        return new GraphDiscoveryConfiguration(
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
        ModelSourceWorkbenchPanel workbench = new ModelSourceWorkbenchPanel(model);
        workbench.edit(SingleRootClassModelPanel.ConfigurationSection.GRAPH_CONSTRAINTS);
        text(find(workbench, GraphConstraintsPanel.class), "graph.edgeProperty")
                .setText("P279");

        workbench.abandonEdits();
        model.graphDiscoveryConfiguration(null);
        workbench.applyEdits();

        assertNull(model.graphDiscoveryConfiguration(),
                "an abandoned draft belongs to the domain that was closed, not the next one");
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
}
