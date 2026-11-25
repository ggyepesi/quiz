package domain.workbench.modelbuilder.ui;

import domain.config.BuildOperation;
import domain.config.ClassConfigurationProjection;
import domain.config.ClassKind;
import domain.config.EntityBound;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.config.GeneratedProjectModelStore;
import domain.config.GraphClassSource;
import domain.config.ClassPopulationMode;
import domain.config.FieldCardinality;
import domain.config.PopulationSelection;
import domain.config.QualifierDateMode;
import domain.config.FieldProductionKind;
import domain.config.StatementClassSource;
import domain.config.TransformationClassSource;
import domain.config.TransformationStep;
import domain.datasource.schema.FieldType;
import domain.persistence.ProjectSaveGuard;
import domain.runtime.SnapshotDomain;
import domain.runtime.WikidataDynamicObject;
import domain.transform.FilterOperator;
import domain.transform.pipeline.ui.TransformationConfigurationEditor;
import domain.transform.ui.ProjectBacking;
import domain.transform.ui.TransformController;
import domain.transform.ui.WorkbenchPurpose;
import domain.workbench.configuration.ui.ProjectConfigurationPanel;
import domain.datasource.graph.GraphDiscoveryConfiguration;
import domain.datasource.graph.GraphExpansionPolicy;
import domain.datasource.graph.GraphRelation;
import domain.datasource.graph.GraphTraversalDirection;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTree;
import javax.swing.JTextField;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassConfigurationTreeTest {
    @Test void savedNobelStatementAndAllItsFieldsShowTheirConfiguration() throws Exception {
        var store = new GeneratedProjectModelStore();
        var model = store.load(new File("../data/wikidata/nobelprizes/nobelprizes.model.json"));
        var before = model.copy();
        var laureates = model.findClass("LaureatesWithMotivation");

        SwingUtilities.invokeAndWait(() -> {
            try (var workbench = new ModelSourceWorkbenchPanel(model)) {
                workbench.edit(laureates);
                assertTrue(showsChoice(workbench, ClassKind.STATEMENT));
                var statement = find(workbench, StatementSourcePanel.class);
                var tree = statement.configurationEditor().tree();
                var triple = find(statement, TripleEditor.class);
                assertEquals("P166", triple.propertyPid());
                assertEquals("award received", triple.propertyLabelText());
                assertEquals(EntityBound.unbounded(), triple.subjectBound());
                assertEquals("", triple.subjectPopulation());
                assertEquals(EntityBound.vocabulary("Categories"), triple.objectBound());
                assertEquals(EntityBound.unbounded(), all(triple, EntityEndEditor.class).get(2).bound());
                assertTrue(tree.selectedNode().caption().contains("Categories"));
                assertTrue(summary(triple).contains("objects: selection 'Categories'"));

                assertTrue(tree.selectDetail(ClassConfigurationProjection.IDENTITY));
                var identity = find(statement, ClassIdentityEditor.class);
                assertTrue(visibleIn(identity, statement));
                assertEquals(List.of("category", "year", "motivation"), find(identity, OrderedChoiceList.class).chosen());
                assertTrue(showsChoice(identity, laureates.canonical().missingKeyPolicy()));

                assertTrue(tree.selectDetail(ClassConfigurationProjection.DISPLAY_NAME));
                var display = find(statement, DisplayNameEditor.class);
                assertTrue(visibleIn(display, statement));
                assertEquals(laureates.canonical().displayNameMode(), display.mode());
                assertEquals(List.of("laureates", "category"), find(display, OrderedChoiceList.class).chosen());

                assertTrue(tree.selectDetail(ClassConfigurationProjection.GRAPH_EXPANSION));
                assertTrue(showsChoice(statement, GraphExpansionPolicy.NONE));

                assertEquals(List.of("category", "year", "laureates", "motivation"),
                        laureates.fields().stream().map(field -> field.name()).toList());
                for (var field : laureates.fields()) {
                    workbench.edit(field);
                    var editor = find(workbench, FieldSourcePanel.class);
                    assertEquals("Produced by", editor.configurationTree().selectedNode().caption());
                    assertTrue(all(editor, JTextField.class).stream().anyMatch(input ->
                            visibleIn(input, editor) && field.name().equals(input.getText())));
                    assertTrue(showsChoice(editor, field.type()));
                    assertTrue(showsChoice(editor, field.cardinality()));
                    assertTrue(showsChoice(editor, field.renderMode()));
                    assertTrue(showsChoice(editor, field.mapping().productionKind()));
                    if (!field.entityClassName().isBlank()) {
                        assertTrue(showsChoice(editor, field.entityClassName()));
                    }
                    assertFalse(visibleIn(editor.propertyPidField(), editor));
                    assertFalse(all(editor, JLabel.class).stream().anyMatch(label ->
                            visibleIn(label, editor) && "Found on:".equals(label.getText())));
                    String qualifier = field.mapping().qualifierPid();
                    if (qualifier.isBlank()) {
                        assertEquals(FieldProductionKind.STATEMENT_OBJECT, field.mapping().productionKind());
                        assertTrue(editor.configurationTree().nodes().stream().anyMatch(node ->
                                node.caption().equals("Primary: Statement object")));
                    } else {
                        assertTrue(editor.configurationTree().nodes().stream().anyMatch(node ->
                                node.caption().startsWith("Primary: Wikidata qualifier")
                                        && node.caption().contains(qualifier)));
                        assertTrue(all(editor, wikidata.ui.WikidataIdText.class).stream().anyMatch(link ->
                                visibleIn(link, editor) && qualifier.equals(link.plainText())));
                    }
                    if (field.name().equals("year")) assertTrue(showsChoice(editor, QualifierDateMode.DATE));
                    assertTrue(editor.selectConfigurationDetail(domain.config.FieldConfigurationProjection.REFINE));
                    assertTrue(showsChoice(editor, field.expectation()));
                    var required = all(editor, JCheckBox.class).stream()
                            .filter(check -> visibleIn(check, editor) && check.getText().startsWith("Required ("))
                            .findFirst().orElseThrow();
                    assertEquals(field.required(), required.isSelected());
                }
                assertTrue(store.sameConfiguration(before, model));
            }
        });
    }

    @Test void eachSupportedStatementBoundCombinationReachesTheEditor() throws Exception {
        var model = new GeneratedProjectModel();
        var statement = new GeneratedClassModel("Award");
        statement.classKind(ClassKind.STATEMENT);
        model.rootClass(statement);
        model.addSelection(new domain.config.VocabularySelection("Categories"));
        var bounds = List.of(EntityBound.unbounded(), EntityBound.explicit(List.of("Q1", "Q2")),
                EntityBound.vocabulary("Categories"), EntityBound.relation("P31", List.of("Q5"), true));

        SwingUtilities.invokeAndWait(() -> {
            var editor = new TripleEditor();
            for (var subject : bounds) {
                for (var object : bounds) {
                    for (var discovery : bounds.subList(0, 3)) {
                        var source = new StatementClassSource("P166");
                        source.subjectBound(subject);
                        source.objectBound(object);
                        source.discoveryObjectBound(discovery);
                        statement.statementSource(source);
                        editor.show(statement, model);
                        assertEquals(subject, editor.subjectBound());
                        assertEquals(object, editor.objectBound());
                        assertEquals(discovery, all(editor, EntityEndEditor.class).get(2).bound());
                        assertTrue(summary(editor).replace("\n", " · ").endsWith(source.describeTriple()));
                    }
                }
            }
        });
    }

    @Test void nobelOpensTheSelectedClassProductionAndTheSelectedFieldSource() throws Exception {
        var model = new GeneratedProjectModelStore().load(new File(
                "../data/wikidata/nobelprizes/nobelprizes.model.json"));
        var person = model.findClass("Person");
        var birth = person.fields().stream().filter(field -> field.name().equals("dateOfBirth"))
                .findFirst().orElseThrow();
        assertTrue(person.isImported());

        SwingUtilities.invokeAndWait(() -> {
            var backing = new FixtureProject(model, new ArrayList<>());
            var controller = new TransformController(new SnapshotDomain(List.of()), null);
            try (var panel = ProjectConfigurationPanel.editWorkingConfiguration(backing, controller,
                    ignored -> { }, ignored -> { }, () -> { }, () -> { })) {
                var navigator = find(panel, SingleRootClassModelPanel.class);
                var projectTree = find(navigator, JTree.class);
                var aggregate = find(panel, AggregateClassPanel.class);
                var aggregateSettings = aggregate.configurationEditor().tree();
                assertEquals("Aggregate from LaureatesWithMotivation", aggregateSettings.selectedNode().caption());
                var grouping = aggregateSettings.editorDetails().stream()
                        .filter(Component::isVisible)
                        .filter(detail -> find(detail, OrderedChoiceList.class) != null)
                        .findFirst().orElseThrow();
                assertEquals("LaureatesWithMotivation", find(grouping, JComboBox.class).getSelectedItem());
                var groups = all(grouping, OrderedChoiceList.class);
                assertEquals(List.of("laureatesWithMotivation"), groups.get(0).chosen());
                assertEquals(List.of("category", "year"), groups.get(1).chosen());
                assertTrue(aggregateSettings.nodes().stream().noneMatch(node -> "NobelPrize".equals(node.caption())));

                var root = (DefaultMutableTreeNode) projectTree.getModel().getRoot();
                var personNode = java.util.Collections.list(root.children()).stream()
                        .map(DefaultMutableTreeNode.class::cast).filter(node -> node.getUserObject() == person)
                        .findFirst().orElseThrow();
                var personPath = new TreePath(personNode.getPath());
                projectTree.collapsePath(personPath);
                projectTree.setSelectionPath(personPath);
                assertTrue(projectTree.isExpanded(personPath));
                var source = find(panel, ClassSourcePanel.class);
                assertEquals("Population: Referenced only", source.configurationEditor().tree().selectedNode().caption());
                var triple = find(source, TripleEditor.class);
                assertFalse(triple.showsTripleControls());
                var status = all(triple, wikidata.ui.WikidataIdText.class).stream()
                        .filter(label -> "class.population.status".equals(label.getName()))
                        .findFirst().orElseThrow();
                assertTrue(status.plainText().contains("Ready — Referenced only"), status.plainText());

                var birthNode = java.util.Collections.list(personNode.children()).stream()
                        .map(DefaultMutableTreeNode.class::cast).filter(node -> node.getUserObject() == birth)
                        .findFirst().orElseThrow();
                var birthPath = new TreePath(birthNode.getPath());
                assertTrue(projectTree.isVisible(birthPath));
                projectTree.setSelectionPath(birthPath);
                assertSame(birth, panel.selectedConstruct());
                var editor = find(panel, FieldSourcePanel.class);
                assertEquals("Produced by", editor.configurationTree().selectedNode().caption());
                assertEquals("P569", editor.propertyPidField().getText());
                assertFalse(editor.propertyPidField().isEditable());
                assertTrue(editor.configurationTree().editorDetails().stream()
                        .filter(Component::isVisible)
                        .anyMatch(detail -> SwingUtilities.isDescendingFrom(editor.propertyPidField(), detail)));
            }
        });
    }

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
        var uses = tree.children().stream().filter(node -> node.caption().equals("Uses"))
                .findFirst().orElseThrow();
        assertTrue(uses.children().stream().anyMatch(
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

    @Test void bothWorkbenchesShowTheModelBuilderTreeAndOnlyTransformCanApplyItsFilters()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var backing = transformationProject();
            var model = backing.projectModel();
            var output = model.findClass("SelectedPosition");
            var controller = new TransformController(new SnapshotDomain(List.of()), null);
            try (var builder = new ModelSourceWorkbenchPanel(model);
                 var transform = new ProjectConfigurationPanel(backing, controller,
                         WorkbenchPurpose.TRANSFORM, List.of(), output, () -> { });
                 var sources = new ProjectConfigurationPanel(backing, controller,
                         WorkbenchPurpose.DATASOURCE, List.of(), output, () -> { })) {
                builder.edit(output);
                var builderTree = find(builder, ClassSourcePanel.class).configurationEditor().tree();
                var transformTree = find(transform, ClassSourcePanel.class).configurationEditor().tree();
                var sourceTree = find(sources, ClassSourcePanel.class).configurationEditor().tree();
                assertEquals(builderTree.nodes(), transformTree.nodes());
                assertEquals(builderTree.nodes(), sourceTree.nodes());

                var second = transformTree.nodes().stream()
                        .filter(node -> ClassConfigurationProjection.transformationStepIndex(node) == 1)
                        .findFirst().orElseThrow();
                sourceTree.select(second.id());
                builderTree.select(second.id());
                transformTree.select(second.id());
                assertFalse(button(find(sources, TransformationConfigurationEditor.class),
                        "Move up").isEnabled());
                assertFalse(button(find(builder, TransformationConfigurationEditor.class),
                        "Move up").isEnabled());
                var transformEditor = find(transform, TransformationConfigurationEditor.class);
                button(transformEditor, "Move up").doClick();
                assertEquals(List.of("holderCount", "siteCount"), output.transformationSource()
                        .steps().stream().map(TransformationStep::fieldPath).toList());
                assertTrue(transform.hasDrafts());

                var constructs = find(transform, SingleRootClassModelPanel.class);
                constructs.selectClass(model.rootClass());
                constructs.selectClass(output);
                button(transformEditor, "Apply transformation").doClick();
                assertEquals(List.of("siteCount", "holderCount"), output.transformationSource()
                        .steps().stream().map(TransformationStep::fieldPath).toList());
                assertFalse(transform.hasDrafts());
                assertEquals(List.of("Population transformation for SelectedPosition"),
                        backing.unsavedModelChanges());

                var imported = model.findClass("ImportedPositions");
                constructs.selectClass(imported);
                transformTree.selectDetail(ClassConfigurationProjection.TRANSFORMATION);
                assertFalse(button(transformEditor, "Add filter…").isEnabled());
            }
        });
    }

    @Test void movingAFilterTwiceKeepsItSelectedAndTheStatusReportsOnlyAppliedChanges()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var backing = transformationProject();
            var output = backing.projectModel().findClass("SelectedPosition");
            output.transformationSource().addStep(TransformationStep.filter(
                    "holderCount", FilterOperator.GREATER_THAN, 10, null));
            var controller = new TransformController(new SnapshotDomain(List.of()), null);
            try (var transform = new ProjectConfigurationPanel(backing, controller,
                    WorkbenchPurpose.TRANSFORM, List.of(), output, () -> { })) {
                var tree = find(transform, ClassSourcePanel.class).configurationEditor().tree();
                var editor = find(transform, TransformationConfigurationEditor.class);
                var status = java.util.Arrays.stream(editor.getComponents())
                        .filter(component -> "transformation.status".equals(component.getName()))
                        .map(JLabel.class::cast).findFirst().orElseThrow();
                assertFalse(status.getText().contains("Applied"));
                tree.select(tree.nodes().stream().filter(node ->
                        ClassConfigurationProjection.transformationStepIndex(node) == 2)
                        .findFirst().orElseThrow().id());

                button(editor, "Move up").doClick();
                assertEquals("2. holderCount > 10", tree.selectedNode().caption());
                button(editor, "Move up").doClick();
                assertEquals("1. holderCount > 10", tree.selectedNode().caption());
                assertFalse(status.getText().contains("Applied"));
                button(editor, "Discard changes").doClick();
                assertFalse(transform.hasDrafts());
                assertFalse(status.getText().contains("Applied"));
                assertTrue(backing.unsavedModelChanges().isEmpty());

                button(editor, "Move down").doClick();
                button(editor, "Apply transformation").doClick();
                assertTrue(status.getText().contains("Applied to the working model"));
                assertTrue(status.getText().contains("Build project"));
            }
        });
    }

    @Test void sourceDetailsStayReadOnlyAfterSelectingRowsAndPopulationEditsAreExplicit()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var backing = transformationProject();
            var model = backing.projectModel();
            var population = (PopulationSelection) model.findSelection("Hand-picked positions");
            var value = new WikidataDynamicObject("Q2", "Another position");
            value.type("Position");
            var controller = new TransformController(new SnapshotDomain(List.of(value)), null);
            try (var transform = new ProjectConfigurationPanel(backing, controller,
                         WorkbenchPurpose.TRANSFORM, List.of(value), population, () -> { });
                 var sources = new ProjectConfigurationPanel(backing, controller,
                         WorkbenchPurpose.DATASOURCE, List.of(value), population, () -> { })) {
                var readOnly = find(sources, SelectionViewerPanel.class);
                assertFalse(button(readOnly, "Add selected instances").isVisible());
                assertEquals(List.of("Q1"), population.instanceQids());
                var editable = find(transform, SelectionViewerPanel.class);
                button(editable, "Add selected instances").doClick();
                assertEquals(List.of("Q1", "Q2"), population.instanceQids());
                assertEquals(List.of("Hand-picked positions"), backing.unsavedModelChanges());
                assertFalse(button(editable, "Add selected instances").isEnabled());

                var navigation = find(sources, SingleRootClassModelPanel.class);
                navigation.selectClass(model.rootClass());
                var source = find(sources, ClassSourcePanel.class);
                var tree = source.configurationEditor().tree();
                tree.selectDetail(ClassConfigurationProjection.IDENTITY);
                var identity = tree.editorDetails().stream()
                        .filter(Component::isVisible)
                        .filter(detail -> find(detail, OrderedChoiceList.class) != null)
                        .findFirst().orElseThrow();
                var list = find(identity, OrderedChoiceList.class);
                assertNotNull(list);
                find(list, JList.class).setSelectedIndex(0);
                assertFalse(button(list, "Remove").isEnabled());
                assertFalse(button(list, "Down").isEnabled());
                var store = new GeneratedProjectModelStore();
                var before = assertDoesNotThrow(() -> store.toJson(model.copy()));
                for (var node : tree.nodes()) {
                    tree.expand(node.id());
                    tree.select(node.id());
                }
                assertEquals(before, assertDoesNotThrow(() -> store.toJson(model.copy())));
            }
        });
    }

    static FixtureProject transformationProject() {
        var model = new GeneratedProjectModel();
        model.name("Positions");
        var base = new GeneratedClassModel("Position");
        base.seedQids().add("Q1");
        base.addField("holderCount", FieldType.NUMBER, FieldCardinality.SINGLE);
        base.addField("siteCount", FieldType.NUMBER, FieldCardinality.SINGLE);
        base.canonical().keyFields().addAll(List.of("holderCount", "siteCount"));
        model.rootClass(base);
        for (String name : List.of("SelectedPosition", "ImportedPositions")) {
            var output = new GeneratedClassModel(name);
            output.baseClassName("Position");
            output.populationMode(ClassPopulationMode.MATERIALIZED_TRANSFORM);
            var recipe = new TransformationClassSource();
            recipe.inputClassReference(base.declarationId(), base.className());
            recipe.addStep(TransformationStep.filter("holderCount", FilterOperator.GREATER_THAN, 0, null));
            recipe.addStep(TransformationStep.filter("siteCount", FilterOperator.GREATER_THAN, 2, null));
            output.transformationSource(recipe);
            if (name.startsWith("Imported")) output.importedFrom("Position library");
            model.addClass(output);
        }
        var selected = new PopulationSelection("Hand-picked positions");
        selected.className("Position");
        selected.instanceQids(List.of("Q1"));
        model.replaceSelection(selected);
        model.ensureDeclarationIdentities();
        return new FixtureProject(model, new ArrayList<>());
    }

    record FixtureProject(GeneratedProjectModel projectModel, List<String> changes)
            implements ProjectBacking {
        @Override public String projectName() { return projectModel.name(); }
        @Override public GeneratedProjectModel.ProjectKind projectKind() { return projectModel.projectKind(); }
        @Override public File modelFile() { return new File("positions.model.json"); }
        @Override public File snapshotFile() { return new File("positions.snapshot.json"); }
        @Override public List<String> unsavedModelChanges() { return List.copyOf(changes); }
        @Override public void recordModelChange(String change) {
            if (!changes.contains(change)) changes.add(change);
        }
        @Override public void populationChanged(PopulationSelection population) { changes.add(population.name()); }
        @Override public String modelWriteConflict() { return ""; }
        @Override public void writeProjectModel() { }
        @Override public <T> T saveProject(Runnable prepare, ProjectSaveGuard.Write<T> saveSnapshot)
                throws Exception {
            prepare.run();
            return saveSnapshot.run();
        }
    }

    private static JButton button(Container parent, String label) {
        JButton found = findButton(parent, label);
        assertNotNull(found, label);
        return found;
    }

    private static JButton findButton(Container parent, String label) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton button && label.equals(button.getText())) return button;
            if (child instanceof Container nested) {
                JButton found = findButton(nested, label);
                if (found != null) return found;
            }
        }
        return null;
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

    private static <T extends Component> List<T> all(Container container, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) found.add(type.cast(child));
            if (child instanceof Container nested) found.addAll(all(nested, type));
        }
        return found;
    }

    private static String summary(TripleEditor editor) {
        return all(editor, wikidata.ui.WikidataIdText.class).stream()
                .filter(label -> "class.population.status".equals(label.getName()))
                .findFirst().orElseThrow().plainText();
    }

    private static boolean showsChoice(Container panel, Object value) {
        return all(panel, JComboBox.class).stream().anyMatch(combo ->
                visibleIn(combo, panel) && value.equals(combo.getSelectedItem()));
    }

    private static boolean visibleIn(Component component, Container ancestor) {
        for (Component current = component; current != null; current = current.getParent()) {
            if (!current.isVisible()) return false;
            if (current == ancestor) return true;
        }
        return false;
    }
}
