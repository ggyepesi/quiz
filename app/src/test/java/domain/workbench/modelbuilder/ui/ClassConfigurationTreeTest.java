package domain.workbench.modelbuilder.ui;

import domain.config.BuildOperation;
import domain.config.ClassConfigurationProjection;
import domain.config.ClassKind;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.config.GeneratedProjectModelStore;
import domain.config.GraphClassSource;
import domain.datasource.graph.GraphDiscoveryConfiguration;
import domain.datasource.graph.GraphRelation;
import domain.datasource.graph.GraphTraversalDirection;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassConfigurationTreeTest {
    @Test void theHistoricalPositionsTransformationShowsItsFilterAndInput() throws Exception {
        var project = new GeneratedProjectModelStore().load(new File(
                "../data/wikidata/historicalpositions/historicalpositions.model.json"));
        var output = project.findClass("PositionWithHolders");
        var tree = ClassConfigurationProjection.project(project, output, output.classKind());

        var population = tree.children().stream()
                .filter(node -> node.detailKey().equals(ClassConfigurationProjection.TRANSFORMATION))
                .findFirst().orElseThrow();
        assertEquals(List.of("1. holderCount > 0"),
                population.children().stream().map(node -> node.caption()).toList());
        var needs = tree.children().stream().filter(node -> node.caption().equals("Needs"))
                .findFirst().orElseThrow();
        assertTrue(needs.children().stream().anyMatch(
                node -> node.caption().equals("transformation input class: Position")));
    }

    @Test void classSectionsShareTheSameDraftUntilApply() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            GeneratedProjectModel project = new GeneratedProjectModel();
            GeneratedClassModel person = new GeneratedClassModel("Person");
            project.rootClass(person);
            ClassSourcePanel panel = new ClassSourcePanel();
            panel.setProjectModel(project);
            panel.edit(person);
            var tree = panel.configurationEditor().tree();
            TripleEditor triple = find(panel, TripleEditor.class);
            assertNotNull(triple);

            assertTrue(tree.selectDetail(ClassConfigurationProjection.PRODUCTION));
            assertTrue(SwingUtilities.isDescendingFrom(triple, panel));
            triple.propertyPid("P31", "instance of");
            triple.objectQids(List.of("Q5"));
            for (var section : tree.nodes()) tree.select(section.id());
            assertTrue(person.membership().qids().isEmpty());
            assertEquals(List.of("Q5"), triple.objectQids());

            panel.applyEdits();
            assertEquals(List.of("Q5"), person.membership().qids());
            assertTrue(tree.nodes().stream().anyMatch(node -> node.caption().equals("Population: Single type")));
        });
    }

    @Test void openingAGraphUsageRevealsItsNodeFromAReadOnlyClass() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var project = new GeneratedProjectModel();
            var position = new GeneratedClassModel("Position");
            position.importedFrom("Historical Positions");
            project.rootClass(position);
            var start = new GeneratedClassModel("Start");
            project.addClass(start);
            var graph = new GeneratedClassModel("PositionGraph");
            graph.classKind(ClassKind.GRAPH);
            graph.graphSource(new GraphClassSource(
                    new GraphDiscoveryConfiguration.StartNode("Start",
                            GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY),
                    List.of(new GraphDiscoveryConfiguration.NextNode(
                            new GraphRelation("wikidata", "P31"), GraphTraversalDirection.OUTGOING,
                            GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION, "Position", null))));
            project.addClass(graph);
            project.ensureDeclarationIdentities();
            project.buildOperations(List.of(
                    new BuildOperation(BuildOperation.Kind.RUN_GRAPH_CONSTRAINT, graph.declarationId())));

            try (var workbench = new ModelSourceWorkbenchPanel(project)) {
                workbench.onConfigurationNavigation(workbench::changeSelection);
                workbench.edit(position);
                var source = find(workbench, ClassSourcePanel.class);
                var tree = source.configurationEditor().tree();
                var usage = tree.nodes().stream()
                        .filter(node -> node.referenceTarget() != null
                                && node.referenceTarget().displayName().equals("PositionGraph node 1"))
                        .findFirst().orElseThrow();

                tree.select(usage.id());
                var graphPanel = find(workbench, GraphConstraintsPanel.class);
                assertTrue(source.isVisible());
                assertTrue(tree.openReferenceButton().isEnabled());
                tree.openReferenceButton().doClick();
                assertTrue(graphPanel.isVisible());
                assertEquals("path/node:0", graphPanel.configurationTree().selectedNode().id().key());
                assertEquals(graph, graphPanel.editing());

                var graphTree = graphPanel.configurationTree();
                var buildUse = graphTree.nodes().stream()
                        .filter(node -> node.caption().equals("build target: Build step 1 — Run graph"))
                        .findFirst().orElseThrow();
                graphTree.select(buildUse.id());
                assertFalse(graphTree.openReferenceButton().isEnabled(),
                        "build use is readable until build steps have their own detail editor");
            }
        });
    }

    private static <T extends Component> T find(Container container, Class<T> type) {
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) {
                T found = find(nested, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
