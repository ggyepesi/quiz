package domain.workbench.unified.ui;

import dataset.DomainStorage;
import domain.build.GenerationStepOutputStore;
import domain.build.GraphDiscoveryResultStore;
import domain.build.GraphResults;
import domain.build.ProjectSave;
import domain.compile.transform.SelfReferenceLedger;
import domain.config.BuildOperation;
import domain.config.ClassConfigurationProjection;
import domain.config.ClassKind;
import domain.config.GraphClassSource;
import domain.config.ClassPopulationMode;
import domain.transform.pipeline.ui.FilterGroupsPanel;
import objectview.field.FieldPath;
import objectview.render.GroupMembersView;
import objectview.viewconfig.ViewConfigEditor;
import domain.config.FieldCardinality;
import domain.config.FieldConfigurationProjection;
import domain.config.FieldSourceType;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedFieldModel;
import domain.config.GeneratedProjectModel;
import domain.config.GeneratedProjectModelStore;
import domain.config.PopulationSelection;
import domain.config.TransformationClassSource;
import domain.config.TransformationStep;
import domain.curation.CorrectionsSidecar;
import domain.curation.ManualCuration;
import domain.datasource.schema.FieldType;
import domain.datasource.graph.GraphDiscoveryState;
import domain.datasource.graph.GraphDiscoveryConfiguration;
import domain.datasource.graph.GraphRelation;
import domain.datasource.graph.GraphTraversalDirection;
import domain.runtime.SnapshotDomain;
import domain.runtime.WikidataDynamicObject;
import domain.transform.FilterOperator;
import domain.transform.app.DomainCatalog;
import domain.transform.pipeline.ui.TransformationConfigurationEditor;
import domain.transform.ui.ProjectBacking;
import domain.workbench.modelbuilder.ui.SingleRootClassModelPanel;
import domain.workbench.execution.ui.QueryObjectResultPanel;
import domain.workbench.execution.ui.SwingQuerySession;
import domain.workbench.ui.ConfigurationTreePanel;
import domain.workbench.ui.WorkbenchActionResultNavigator;
import domain.workbench.ui.WorkbenchResultViews;
import objectview.view.SearchableView;
import wikidata.FakeWikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.query.core.WikidataAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import work.QueryContext;
import work.WorkflowRecorder;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTabbedPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedWorkbenchPanelTest {
    @TempDir Path root;

    @Test void runsTheSelectedGraphAndKeepsAnnotationsOnlyWhenAsked() throws Exception {
        var storage = DomainStorage.in(root.toFile());
        var model = positionGraphProject();
        var graph = model.findClass("PositionGraph");
        var member = new WikidataDynamicObject("Q1", "Position Q1");
        member.type("Position");
        var run = new ProjectSave.Run(List.of(member), List.of(), null, model);
        var previousCandidate = new WikidataDynamicObject("Q2", "Previous position Q2");
        previousCandidate.type("Position");
        var previous = new WikidataDynamicObject("Q2", "Previous position Q2");
        previous.type(graph.className());
        previous.put(GraphDiscoveryResultStore.ANNOTATED_INSTANCE, previousCandidate);
        previous.put(GraphDiscoveryResultStore.GRAPH_DECISION, "Accepted");
        GraphDiscoveryResultStore.manualDecision(previous, "Accepted");
        var graphResults = new GraphResults(model, storage);
        graphResults.record(graph, new GraphDiscoveryResultStore.Artifact(model.name(), graph.className(),
                "Position", List.of(previous), List.of(previousCandidate), new SnapshotDomain(List.of(previous),
                        GraphDiscoveryResultStore.fieldGraph(graph.className(), "Position", List.of(previous)))));
        ProjectSave.plan(new ProjectSave.Input(model, run, null, graphResults.all()), storage).write();
        var snapshot = storage.snapshotFile(model.name()).toPath();
        var before = Files.readString(snapshot);
        var graphFile = GraphDiscoveryResultStore.destination(storage, model.name(), graph.className());
        var beforeGraph = Files.readString(graphFile.toPath());
        var opened = DomainCatalog.modelBacked(storage).getFirst().opener().open();
        var backing = opened.capability(ProjectBacking.class);
        var requests = new AtomicInteger();
        var api = new WikidataApiClient("test") {
            @Override public PartialStatements getStatementsByPropertyPartial(
                    List<String> qids, List<String> pids, BatchLog log,
                    StatementBatchCommitter committer) throws Exception {
                assertEquals(List.of("Q1"), qids, "the graph starts from the open project's loaded instances");
                var statements = Map.of("P31", Map.of("Q1", List.of(
                        new ApiStatement("Q1$P31", "Q2", Map.of()))));
                requests.incrementAndGet();
                committer.commit(qids, statements);
                return new PartialStatements(statements, 0, List.of());
            }
            @Override public PartialEntities getEntitiesBestEffort(
                    List<String> qids, List<String> pids,
                    java.util.Collection<wikidata.api.FactDemand.EntityMetadata> metadata, BatchLog log) {
                return new PartialEntities(Map.of(), 0, List.of());
            }
            @Override public Map<String, ApiEntity> getEntities(
                    List<String> qids, List<String> pids,
                    java.util.Collection<wikidata.api.FactDemand.EntityMetadata> metadata, BatchLog log) {
                return Map.of();
            }
        };
        var panel = new AtomicReference<UnifiedWorkbenchPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var workbench = new UnifiedWorkbenchPanel(opened,
                    new SwingQuerySession(WikidataAccess.of(new FakeWikidataSparqlClient(), api).bind()));
            panel.set(workbench);
            workbench.showSaveSuccess(false);
            workbench.persistenceConfirmation(message -> message.contains(graphFile.getPath()));
            selectAction(workbench, "Run graph");
            var plan = find(workbench, JButton.class, b -> "Plan graph run".equals(b.getText()));
            assertFalse(plan.isEnabled(), "ordinary classes do not run as graphs");
            find(workbench, SingleRootClassModelPanel.class, unused -> true)
                    .selectClass(backing.projectModel().findClass(graph.className()));
            plan.doClick();
            assertEquals(0, requests.get(), "the shared plan only inspects the inputs");
            find(workbench, JButton.class, b -> "Execute".equals(b.getText())).doClick();
        });
        try {
            var workbench = panel.get();
            await(() -> hasButton(workbench, "Keep annotations"), "the graph did not finish");
            SwingUtilities.invokeAndWait(() -> {
                find(workbench, JButton.class, b -> "Close without keeping annotations".equals(b.getText())).doClick();
                assertTrue(backing.unsavedModelChanges().isEmpty());
                selectAction(workbench, "Review graph decisions");
                find(workbench, JButton.class, b -> "Load graph annotations".equals(b.getText())).doClick();
            });
            await(() -> descendants(workbench).filter(QueryObjectResultPanel.class::isInstance)
                    .map(QueryObjectResultPanel.class::cast)
                    .anyMatch(view -> view.displayedObjectsByType().containsKey(graph.className())),
                    "closing the run left the previous saved graph available");
            assertEquals(beforeGraph, Files.readString(graphFile.toPath()));
            SwingUtilities.invokeAndWait(() -> {
                selectAction(workbench, "Run graph");
                find(workbench, JButton.class, b -> "Plan graph run".equals(b.getText())).doClick();
                find(workbench, JButton.class, b -> "Execute".equals(b.getText())).doClick();
            });
            await(() -> hasButton(workbench, "Keep annotations"), "the second graph run did not finish");
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(actionNode(workbench, "Accepted — 1 total (1)") != null);
                find(workbench, JButton.class, b -> "Keep annotations".equals(b.getText())).doClick();
                assertEquals(List.of("Graph annotations for PositionGraph"), backing.unsavedModelChanges());
            });
            assertEquals(beforeGraph, Files.readString(graphFile.toPath()),
                    "keeping the annotations changes only the open project");
            SwingUtilities.invokeAndWait(() ->
                    find(workbench, JButton.class, b -> "Save domain…".equals(b.getText())).doClick());
            awaitSaved(workbench);
            assertEquals(2, requests.get());
            var saved = new GraphResults(backing.projectModel(), storage)
                    .restore(backing.projectModel().findClass(graph.className()), ignored -> { });
            assertEquals(java.util.Set.of("Q2"), saved.acceptedIdentities());
            assertEquals("Accepted", saved.instances().getFirst().get(GraphDiscoveryResultStore.MANUAL_DECISION));
            assertFalse(saved.applied(), "the kept annotation result is ready for the build's Apply step");
            assertEquals(before, Files.readString(snapshot));
        } finally {
            SwingUtilities.invokeAndWait(panel.get()::close);
        }
    }

    private static boolean hasButton(Component panel, String caption) {
        return descendants(panel).filter(JButton.class::isInstance).map(JButton.class::cast)
                .anyMatch(button -> caption.equals(button.getText()));
    }

    private static GeneratedProjectModel positionGraphProject() {
        var model = new GeneratedProjectModel();
        model.name("Offices");
        var positions = model.getOrCreateClass("Position");
        model.rootClass(positions);
        var graph = model.getOrCreateClass("PositionGraph");
        graph.classKind(ClassKind.GRAPH);
        graph.graphSource(new GraphClassSource(
                new GraphDiscoveryConfiguration.StartNode("Position", "",
                        GraphDiscoveryConfiguration.NodeUse.INTERMEDIATE_ONLY),
                List.of(new GraphDiscoveryConfiguration.NextNode(new GraphRelation("wikidata", "P31"),
                        GraphTraversalDirection.OUTGOING, GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION,
                        "Position", null))));
        return model;
    }

    @Test void reviewSavesGraphDecisionsAndTheNextBuildAppliesThemInTheSameWindow() throws Exception {
        var storage = DomainStorage.in(root.toFile());
        var model = positionGraphProject();
        var positions = model.rootClass();
        var graph = model.findClass("PositionGraph");
        model.buildOperations(List.of(new BuildOperation(BuildOperation.Kind.APPLY_GRAPH_DECISIONS,
                graph.declarationId()), new BuildOperation(BuildOperation.Kind.SAVE_PROJECT_RESULT, "")));
        var members = List.of("Q1", "Q2").stream().map(qid -> {
            var member = new WikidataDynamicObject(qid, "Position " + qid);
            member.type("Position");
            return member;
        }).toList();
        var annotations = members.stream().map(member -> {
            var annotation = new WikidataDynamicObject(member.getIdentifier(), member.getDisplayName());
            annotation.type(graph.className());
            annotation.put(GraphDiscoveryResultStore.ANNOTATED_INSTANCE, member);
            annotation.put(GraphDiscoveryResultStore.GRAPH_DECISION, "Review");
            annotation.put(GraphDiscoveryResultStore.REVIEW_DISPOSITION, GraphDiscoveryResultStore.AWAIT_DECISION);
            return annotation;
        }).toList();
        var graphs = new GraphResults(model, storage);
        graphs.record(graph, new GraphDiscoveryResultStore.Artifact(model.name(), graph.className(),
                positions.className(), annotations, members, new SnapshotDomain(annotations,
                        GraphDiscoveryResultStore.fieldGraph(graph.className(), positions.className(), annotations))));
        var run = new ProjectSave.Run(members, List.of(), null, model);
        ProjectSave.plan(new ProjectSave.Input(model, run, null, graphs.all()), storage).write();

        var snapshot = storage.snapshotFile(model.name()).toPath();
        var graphFile = graphs.destination(graph).toPath();
        var before = Files.readString(snapshot);
        var beforeDecisions = Files.readString(graphFile);
        var open = DomainCatalog.modelBacked(storage).getFirst().opener();
        var opened = open.open();
        var backing = opened.capability(ProjectBacking.class);
        var panel = new AtomicReference<UnifiedWorkbenchPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var workbench = new UnifiedWorkbenchPanel(opened);
            panel.set(workbench);
            workbench.showSaveSuccess(false);
            workbench.persistenceConfirmation(message -> message.contains(graphFile.toString()));
            selectAction(workbench, "Build project");
            find(workbench, JButton.class, b -> "Plan build".equals(b.getText())).doClick();
            find(workbench, JButton.class, b -> "Execute".equals(b.getText())).doClick();
        });
        try {
            var workbench = panel.get();
            await(() -> actionNode(workbench, "Graph decisions") != null,
                    "the stopped build did not offer review");
            assertEquals(before, Files.readString(snapshot));
            SwingUtilities.invokeAndWait(() -> startLoadingInstances(workbench));
            await(() -> loadStatus(workbench).startsWith("2 saved instances loaded"),
                    "the project did not reload after the stopped build");
            await(() -> workbench.instancesView().displayedObjectsByType().containsKey(graph.className()),
                    "the standalone instances viewer must include the saved graph");
            SwingUtilities.invokeAndWait(() -> find(workbench.instancesView(), javax.swing.JTabbedPane.class,
                    tabs -> java.util.stream.IntStream.range(0, tabs.getTabCount())
                            .mapToObj(tabs::getTitleAt).anyMatch(title -> title.startsWith("Review"))));
            SwingUtilities.invokeAndWait(() -> {
                selectAction(workbench, "Graph decisions");
                find(workbench, JLabel.class, label -> ("Load " + graphFile).equals(label.getText()));
                find(workbench, JButton.class, b -> "Review PositionGraph".equals(b.getText())).doClick();
                var selected = find(workbench, SingleRootClassModelPanel.class, unused -> true)
                        .selectedUserObject();
                assertTrue(selected instanceof GeneratedClassModel current
                        && current.declarationId().equals(graph.declarationId())
                        && current != backing.projectModel().findClass(graph.className()));
            });
            await(() -> descendants(workbench).filter(QueryObjectResultPanel.class::isInstance)
                    .map(QueryObjectResultPanel.class::cast)
                    .anyMatch(view -> view.displayedObjectsByType().containsKey(graph.className())),
                    "the saved graph did not render");
            SwingUtilities.invokeAndWait(() -> {
                var view = find(workbench, QueryObjectResultPanel.class,
                        cards -> cards.displayedObjectsByType().containsKey(graph.className()));
                var tabs = find(view, JTabbedPane.class, t -> t.getTabCount() == 4);
                assertEquals(List.of("All (2)", "Accepted (0)", "Review (2)", "Rejected (0)"),
                        java.util.stream.IntStream.range(0, 4).mapToObj(tabs::getTitleAt).toList());
                var cards = view.displayedObjectsByType().get(graph.className());
                view.activeRenderContext().select(cards.stream().filter(c -> c.getIdentifier().equals("Q1"))
                        .findFirst().orElseThrow());
                find(view, JButton.class, b -> "Accept selection".equals(b.getText())).doClick();
                view.activeRenderContext().select(cards.stream().filter(c -> c.getIdentifier().equals("Q2"))
                        .findFirst().orElseThrow());
                find(view, JButton.class, b -> "Reject selection".equals(b.getText())).doClick();
                assertEquals("Review (2)", tabs.getTitleAt(2));
                find(workbench, JLabel.class, label -> "Graph annotations have unsaved changes".equals(label.getText()));
            });
            assertEquals(beforeDecisions, Files.readString(graphFile));
            SwingUtilities.invokeAndWait(() ->
                    find(workbench, JButton.class, b -> "Save domain…".equals(b.getText())).doClick());
            awaitSaved(workbench);
            assertEquals(before, Files.readString(snapshot));
            var savedModel = new GeneratedProjectModelStore().load(storage.modelFile(model.name()));
            var savedGraphs = new GraphResults(savedModel, storage);
            var saved = savedGraphs.restore(savedModel.findClass(graph.className()), ignored -> { });
            assertEquals(java.util.Set.of("Q1"), saved.acceptedIdentities());
            assertTrue(saved.awaitingDecision().isEmpty());
            SwingUtilities.invokeAndWait(() -> {
                selectAction(workbench, "Build project");
                find(workbench, JButton.class, b -> "Plan build".equals(b.getText())).doClick();
                find(workbench, JButton.class, b -> "Execute".equals(b.getText())).doClick();
            });
            await(() -> actionNode(workbench, "Instances (1)") != null,
                    "the build did not publish the accepted instance");
            assertEquals(List.of("Q1"), open.open().instancesOf("Position").stream()
                    .map(objectview.Viewable::getIdentifier).toList());
        } finally {
            SwingUtilities.invokeAndWait(panel.get()::close);
        }
    }

    @Test void applyUpdatesThePlanInTheSameWindowBeforeSaveAndReopen() throws Exception {
        var storage = DomainStorage.in(root.toFile());
        var model = new GeneratedProjectModel();
        model.name("Egyptian Gods");
        var deity = new GeneratedClassModel("EgyptianDeity");
        deity.seedQids().add("Q47534");
        var field = deity.addField("symbol", FieldType.TEXT, FieldCardinality.SINGLE);
        field.mapping().sourceType(FieldSourceType.WIKIPEDIA_INFOBOX);
        field.mapping().propertyPid("Rendered.Symbol");
        model.rootClass(deity);
        var anubis = new WikidataDynamicObject("Q47534", "Anubis");
        anubis.type("EgyptianDeity");
        anubis.enwikiTitle("Anubis");
        var run = new ProjectSave.Run(List.of(anubis), List.of(), null, model);
        ProjectSave.plan(new ProjectSave.Input(model, run, null, List.of()), storage).write();

        Path modelPath = storage.modelFile(model.name()).toPath();
        Path snapshotPath = storage.snapshotFile(model.name()).toPath();
        String savedModel = Files.readString(modelPath);
        String savedSnapshot = Files.readString(snapshotPath);
        var choice = DomainCatalog.modelBacked(storage).getFirst();
        var opened = choice.opener().open();
        var working = opened.capability(ProjectBacking.class).projectModel();
        assertSame(working.findClass("EgyptianDeity"), working.rootClass());
        var panel = new AtomicReference<UnifiedWorkbenchPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var workbench = new UnifiedWorkbenchPanel(opened);
            panel.set(workbench);
            find(workbench, SingleRootClassModelPanel.class, unused -> true).selectField(symbol(working));
            var plan = find(workbench, JTextArea.class, area -> "field.acquisition.plan".equals(area.getName()));
            var save = find(workbench, JButton.class, button -> "Save domain…".equals(button.getText()));
            assertTrue(save.isEnabled(), "Save is a complete action and is offered whenever idle");
            assertTrue(plan.getText().contains("Rendered.Symbol"), plan.getText());

            var tree = find(workbench, ConfigurationTreePanel.class,
                    candidate -> candidate.nodes().stream().anyMatch(
                            node -> FieldConfigurationProjection.PRIMARY.equals(node.detailKey())));
            assertTrue(tree.selectDetail(FieldConfigurationProjection.PRIMARY));
            find(workbench, JTextField.class, input -> "field.source.property".equals(input.getName()))
                    .setText("Rendered.Consort");
            assertTrue(plan.getText().contains("Rendered.Symbol"), plan.getText());
            assertTrue(workbench.hasUnappliedEdits());
            var apply = find(workbench, JButton.class, button -> "Apply field source".equals(button.getText()));
            assertTrue(apply.isEnabled());
            apply.doClick();
            assertTrue(plan.getText().contains("Rendered.Consort"), plan.getText());
            assertFalse(plan.getText().contains("Rendered.Symbol"), plan.getText());
            assertTrue(save.isEnabled());
        });
        try {
            assertEquals(savedModel, Files.readString(modelPath));
            panel.get().saveProject();
            assertTrue(Files.readString(modelPath).contains("Rendered.Consort"));
            assertEquals(savedSnapshot, Files.readString(snapshotPath));

            var reopened = choice.opener().open();
            assertEquals(1, reopened.memberRoots().size());
            var persisted = symbol(reopened.capability(ProjectBacking.class).projectModel());
            assertEquals("Rendered.Consort", persisted.mapping().propertyPid());
            SwingUtilities.invokeAndWait(() -> {
                try (var workbench = new UnifiedWorkbenchPanel(reopened)) {
                    find(workbench, SingleRootClassModelPanel.class, unused -> true).selectField(persisted);
                    var plan = find(workbench, JTextArea.class,
                            area -> "field.acquisition.plan".equals(area.getName()));
                    assertTrue(plan.getText().contains("Rendered.Consort"), plan.getText());
                }
            });
        } finally {
            SwingUtilities.invokeAndWait(panel.get()::close);
        }
    }

    /** #535: a construct is dirty when Apply would change the model. A transformation draft
     *  that was edited and then put back as it was asks nothing on leaving, saving or closing. */
    @Test void aRecipeEditThatIsReversedLeavesTheTransformationClean() throws Exception {
        var storage = DomainStorage.in(root.toFile());
        var model = new GeneratedProjectModel();
        model.name("Positions");
        var input = new GeneratedClassModel("Position");
        input.seedQids().add("Q1");
        input.addField("holderCount", FieldType.NUMBER, FieldCardinality.SINGLE);
        input.addField("siteCount", FieldType.NUMBER, FieldCardinality.SINGLE);
        model.rootClass(input);
        var output = new GeneratedClassModel("SelectedPosition");
        output.baseClassName(input.className());
        output.populationMode(ClassPopulationMode.MATERIALIZED_TRANSFORM);
        var recipe = new TransformationClassSource();
        recipe.inputClassReference(input.declarationId(), input.className());
        recipe.addStep(TransformationStep.filter("holderCount", FilterOperator.GREATER_THAN, 0, null));
        recipe.addStep(TransformationStep.filter("siteCount", FilterOperator.GREATER_THAN, 2, null));
        output.transformationSource(recipe);
        model.addClass(output);
        var first = new WikidataDynamicObject("Q1", "First position");
        first.type(input.className());
        var run = new ProjectSave.Run(List.of(first), List.of(), null, model);
        ProjectSave.plan(new ProjectSave.Input(model, run, null, List.of()), storage).write();
        var domain = DomainCatalog.modelBacked(storage).getFirst().opener().open();
        var working = domain.capability(ProjectBacking.class).projectModel().findClass(output.className());
        SwingUtilities.invokeAndWait(() -> {
            try (var workbench = new UnifiedWorkbenchPanel(domain)) {
                find(workbench, SingleRootClassModelPanel.class, unused -> true).selectClass(working);
                var recipeTree = find(workbench, ConfigurationTreePanel.class, tree -> tree.nodes().stream()
                        .anyMatch(node -> ClassConfigurationProjection.transformationStepIndex(node) == 1));
                var editor = find(workbench, TransformationConfigurationEditor.class, unused -> true);
                var up = find(editor, JButton.class, button -> "Move up".equals(button.getText()));
                Runnable selectSecond = () -> recipeTree.select(recipeTree.nodes().stream()
                        .filter(node -> ClassConfigurationProjection.transformationStepIndex(node) == 1)
                        .findFirst().orElseThrow().id());

                selectSecond.run();
                up.doClick();
                assertTrue(workbench.hasUnappliedEdits(), "a reordered recipe is a draft");
                selectSecond.run();
                up.doClick();
                assertFalse(workbench.hasUnappliedEdits(), "the original order is the applied recipe");
                workbench.unappliedDecision((construct, action) -> {
                    throw new AssertionError("a clean transformation asked: " + action);
                });
                boolean[] closed = {false};
                workbench.requestClose(() -> closed[0] = true);
                assertTrue(closed[0]);
            }
        });
        assertEquals(2, working.transformationSource().steps().size());
    }

    @Test void createsAFilterProducedClassPreviewsAndBuildsItInTheSameWindow() throws Exception {
        var storage = DomainStorage.in(root.toFile());
        var model = new GeneratedProjectModel();
        model.name("Positions");
        var input = new GeneratedClassModel("Position");
        input.seedQids().addAll(List.of("Q1", "Q2"));
        input.addField("holderCount", FieldType.NUMBER, FieldCardinality.SINGLE);
        model.rootClass(input);
        var first = new WikidataDynamicObject("Q1", "Held position");
        first.type(input.className());
        first.put("holderCount", 5);
        var second = new WikidataDynamicObject("Q2", "Empty position");
        second.type(input.className());
        second.put("holderCount", 0);
        ProjectSave.plan(new ProjectSave.Input(model,
                new ProjectSave.Run(List.of(first, second), List.of(), null, model),
                null, List.of()), storage).write();
        var modelFile = storage.modelFile(model.name()).toPath();
        var snapshot = storage.snapshotFile(model.name()).toPath();
        String originalModel = Files.readString(modelFile);
        String originalSnapshot = Files.readString(snapshot);
        var domain = DomainCatalog.modelBacked(storage).getFirst().opener().open();
        var working = domain.capability(ProjectBacking.class).projectModel();
        var panel = new AtomicReference<UnifiedWorkbenchPanel>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var workbench = new UnifiedWorkbenchPanel(domain);
                panel.set(workbench);
                selectAction(workbench, "Filter groups");
                var filters = find(workbench, FilterGroupsPanel.class, unused -> true);
                var oldOutline = find(filters, GroupMembersView.class, unused -> true);
                find(filters, ViewConfigEditor.class, unused -> true)
                        .setSelectedPath(FieldPath.of("holderCount"));
                find(filters, JComboBox.class, combo -> "filter.operator".equals(combo.getName()))
                        .setSelectedItem(FilterOperator.GREATER_THAN);
                find(filters, JComboBox.class, combo -> "filter.value".equals(combo.getName()))
                        .getEditor().setItem("0");
                filters.filterGroupNamePrompt(() -> "Has holders");
                find(filters, JButton.class, button -> "Add filter group".equals(button.getText())).doClick();
                assertFalse(oldOutline.selectGroup(oldOutline.groups().getRootGroup(), true),
                        "rebuilding the outline must dispose the old one");
                var newOutline = find(filters, GroupMembersView.class, unused -> true);
                assertEquals("Has holders", newOutline.groups().selectedGroup().getDisplayName());
                assertNull(working.findClass("PositionsWithHolders"),
                        "making a group does not declare a class");
                find(workbench, JTextField.class, field -> "transform.outputClass".equals(field.getName()))
                        .setText("PositionsWithHolders");
                find(workbench, JButton.class, button -> "Create transformed class".equals(button.getText()))
                        .doClick();
                var output = working.findClass("PositionsWithHolders");
                assertTrue(output.materializedByTransformation());
                assertEquals("Position", output.baseClassName());
                assertEquals("holderCount", output.transformationSource().steps().getFirst().fieldPath());
                assertTrue(working.buildOperations().isEmpty(), "never edit the build order implicitly");
                var publication = (PopulationSelection) working.findSelection("PositionsWithHoldersPopulation");
                assertEquals(output.className(), publication.className());
                assertTrue(publication.instanceQids().isEmpty(), "the build publishes the members");
                var tree = find(workbench, SingleRootClassModelPanel.class, unused -> true);
                assertSame(output, tree.selectedUserObject());
                selectAction(workbench, "Preview population");
                find(workbench, JButton.class, button -> "Preview population".equals(button.getText())).doClick();
            });
            var workbench = panel.get();
            await(() -> actionNode(workbench, "Matching instances (1)") != null,
                    "the new class's transformation did not preview");
            assertEquals(originalModel, Files.readString(modelFile));
            assertEquals(originalSnapshot, Files.readString(snapshot));
            assertTrue(domain.instancesOf("PositionsWithHolders").isEmpty());
            SwingUtilities.invokeAndWait(() -> {
                selectAction(workbench, "Build project");
                find(workbench, JButton.class, button -> "Edit build steps".equals(button.getText())).doClick();
                var build = find(workbench, JPanel.class, candidate -> "project.build".equals(candidate.getName()));
                var options = find(build, JComboBox.class, unused -> true);
                for (var operation : List.of(BuildOperation.Kind.GENERATE_PROJECT,
                        BuildOperation.Kind.RUN_TRANSFORMATION,
                        BuildOperation.Kind.PUBLISH_POPULATION, BuildOperation.Kind.SAVE_PROJECT_RESULT)) {
                    int index = java.util.stream.IntStream.range(0, options.getItemCount())
                            .filter(i -> options.getItemAt(i) instanceof BuildOperation step
                                    && step.kind() == operation).findFirst().orElseThrow();
                    options.setSelectedIndex(index);
                    find(build, JButton.class, button -> "Add".equals(button.getText())).doClick();
                }
                workbench.persistenceConfirmation(message -> message.contains(modelFile.toString()));
                workbench.showSaveSuccess(false);
                find(workbench, JButton.class, button -> "Save domain…".equals(button.getText())).doClick();
            });
            awaitSaved(workbench);
            assertEquals(originalSnapshot, Files.readString(snapshot));
            // The test supplies the upstream acquisition output for this saved model;
            // the actual build still runs transformation, publication and project save.
            var savedBeforeBuild = new GeneratedProjectModelStore().load(modelFile.toFile());
            var generate = savedBeforeBuild.buildOperations().getFirst();
            GenerationStepOutputStore.of(storage, savedBeforeBuild.name(), generate.declarationId())
                    .save(savedBeforeBuild, List.of(first, second), List.of(), SelfReferenceLedger.EMPTY,
                            GraphDiscoveryState.EMPTY, Map.of());
            SwingUtilities.invokeAndWait(() -> {
                find(workbench, JButton.class, button -> "Plan build".equals(button.getText())).doClick();
                find(workbench, JButton.class, button -> "Execute".equals(button.getText())).doClick();
            });
            await(() -> find(workbench, JButton.class, button -> "Save domain…".equals(button.getText()))
                    .isEnabled(), "build did not finish");
            var manifest = storage.buildManifestFile(model.name()).toPath();
            var report = Files.isRegularFile(manifest) ? Files.readString(manifest) : "No build manifest";
            assertTrue(actionNode(workbench, "Instances (2)") != null, report);
            await(() -> workbench.instancesView().displayedObjectsByType().containsKey("PositionsWithHolders"),
                    "the built class is not in the open instances viewer");
            assertEquals(List.of("Q1"), workbench.instancesView().displayedObjectsByType()
                    .get("PositionsWithHolders").stream().map(objectview.Viewable::getIdentifier).toList());
            var saved = new GeneratedProjectModelStore().load(modelFile.toFile());
            assertEquals(List.of("Q1"), ((PopulationSelection) saved.findSelection(
                    "PositionsWithHoldersPopulation")).instanceQids());
        } finally {
            if (panel.get() != null) SwingUtilities.invokeAndWait(panel.get()::close);
        }
    }

    @Test void editPreviewSaveAndBuildUpdateTheSameOpenProject() throws Exception {
        var storage = DomainStorage.in(root.toFile());
        var model = new GeneratedProjectModel();
        model.name("Positions");
        var input = new GeneratedClassModel("Position");
        input.seedQids().addAll(List.of("Q1", "Q2", "Q3"));
        input.addField("holderCount", FieldType.NUMBER, FieldCardinality.SINGLE);
        input.addField("siteCount", FieldType.NUMBER, FieldCardinality.SINGLE);
        model.rootClass(input);
        var output = new GeneratedClassModel("SelectedPosition");
        output.baseClassName(input.className());
        output.populationMode(ClassPopulationMode.MATERIALIZED_TRANSFORM);
        var recipe = new TransformationClassSource();
        recipe.inputClassReference(input.declarationId(), input.className());
        recipe.addStep(TransformationStep.filter("holderCount", FilterOperator.GREATER_THAN, 0, null));
        recipe.addStep(TransformationStep.filter("siteCount", FilterOperator.GREATER_THAN, 2, null));
        output.transformationSource(recipe);
        model.addClass(output);
        var population = new PopulationSelection("Positions to publish");
        population.className(output.className());
        model.addSelection(population);
        var generate = new BuildOperation(BuildOperation.Kind.GENERATE_PROJECT, "");
        model.buildOperations(List.of(generate,
                new BuildOperation(BuildOperation.Kind.RUN_TRANSFORMATION, output.declarationId()),
                new BuildOperation(BuildOperation.Kind.PUBLISH_POPULATION, population.declarationId()),
                new BuildOperation(BuildOperation.Kind.SAVE_PROJECT_RESULT, "")));
        var first = new WikidataDynamicObject("Q1", "First position");
        first.type(output.className());
        first.put("holderCount", 5);
        first.put("siteCount", 3);
        var second = new WikidataDynamicObject("Q2", "Second position");
        second.type(input.className());
        second.put("holderCount", 8);
        second.put("siteCount", 1);
        var third = new WikidataDynamicObject("Q3", "Third position");
        third.type(input.className());
        third.put("holderCount", 0);
        var run = new ProjectSave.Run(List.of(first, second, third), List.of(), null, model);
        ProjectSave.plan(new ProjectSave.Input(model, run, null, List.of()), storage).write();
        var baseFirst = first.copyWithoutFields();
        baseFirst.removeClass(output.className());
        baseFirst.type(input.className());
        baseFirst.dynamicFields().putAll(first.dynamicFields());
        GenerationStepOutputStore.of(storage, model.name(), generate.declarationId())
                .save(model, List.of(baseFirst, second, third), List.of(), SelfReferenceLedger.EMPTY,
                        GraphDiscoveryState.EMPTY, Map.of());
        Path modelFile = storage.modelFile(model.name()).toPath();
        Path snapshot = storage.snapshotFile(model.name()).toPath();
        String savedRecipe = Files.readString(modelFile);
        String savedInstances = Files.readString(snapshot);
        var choice = DomainCatalog.modelBacked(storage).getFirst();
        var domain = choice.opener().open();
        var working = domain.capability(ProjectBacking.class).projectModel().findClass(output.className());
        var panel = new AtomicReference<UnifiedWorkbenchPanel>();
        var results = new AtomicReference<WorkbenchResultViews>();
        SwingUtilities.invokeAndWait(() -> {
            var workbench = new UnifiedWorkbenchPanel(domain);
            panel.set(workbench);
            var constructs = find(workbench, SingleRootClassModelPanel.class, unused -> true);
            constructs.selectClass(working);
            var recipeTree = find(workbench, ConfigurationTreePanel.class, tree -> tree.nodes().stream()
                    .anyMatch(node -> ClassConfigurationProjection.transformationStepIndex(node) == 1));
            recipeTree.select(recipeTree.nodes().stream()
                    .filter(node -> ClassConfigurationProjection.transformationStepIndex(node) == 1)
                    .findFirst().orElseThrow().id());
            var editor = find(workbench, TransformationConfigurationEditor.class, unused -> true);
            find(editor, JButton.class, button -> "Remove filter".equals(button.getText())).doClick();

            selectAction(workbench, "Preview population");
            var preview = find(workbench, JButton.class, button -> "Preview population".equals(button.getText()));
            results.set(find(workbench, WorkbenchResultViews.class,
                    tabs -> "transform.preview.results".equals(tabs.getName())));
            workbench.unappliedDecision((construct, action) -> process.swing.Dialogs.UnappliedEdits.STAY);
            preview.doClick();
            assertEquals(0, results.get().getTabCount());
            assertEquals(2, working.transformationSource().steps().size());
            assertTrue(workbench.hasUnappliedEdits());
            workbench.unappliedDecision((construct, action) -> process.swing.Dialogs.UnappliedEdits.APPLY);
            preview.doClick();
            assertEquals(1, working.transformationSource().steps().size());
            assertFalse(preview.isEnabled());
            assertFalse(workbench.hasUnappliedEdits());
        });
        try {
            var workbench = panel.get();
            await(() -> results.get().getTabCount() == 1
                    && find(workbench, JButton.class, button -> "Save domain…".equals(button.getText()))
                            .isEnabled(), "population preview did not finish");
            assertEquals(savedRecipe, Files.readString(modelFile));
            assertEquals(savedInstances, Files.readString(snapshot));
            assertEquals(1, domain.instancesOf(output.className()).size());
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("Matching instances (2)", results.get().getTitleAt(0));
                assertTrue(results.get().getComponentAt(0) instanceof SearchableView);
                selectAction(workbench, "Build project");
                find(workbench, JButton.class, button -> "Plan build".equals(button.getText())).doClick();
                find(workbench, JLabel.class, label -> label.getText().contains("before building.")
                        && label.getText().contains(modelFile.toString()));
                workbench.persistenceConfirmation(message -> message.contains(modelFile.toString()));
                workbench.showSaveSuccess(false);
                find(workbench, JButton.class, button -> "Save domain…".equals(button.getText())).doClick();
            });
            awaitSaved(workbench);
            assertEquals(savedInstances, Files.readString(snapshot));
            var reopened = choice.opener().open();
            var persisted = reopened.capability(ProjectBacking.class).projectModel().findClass(output.className());
            assertEquals(List.of("holderCount"), persisted.transformationSource().steps().stream()
                    .map(TransformationStep::fieldPath).toList());

            var shown = new java.util.concurrent.CopyOnWriteArrayList<UnifiedWorkbenchPanel.InstancesShown>();
            SwingUtilities.invokeAndWait(() -> {
                workbench.onInstancesShown(shown::add);
                find(workbench, JButton.class, button -> "Plan build".equals(button.getText())).doClick();
                find(workbench, JLabel.class, label -> label.getText().contains("Run the saved build of")
                        && label.getText().contains(modelFile.toString()));
                find(workbench, JButton.class, button -> "Execute".equals(button.getText())).doClick();
                assertFalse(find(workbench, JButton.class, button -> "Save domain…".equals(button.getText()))
                        .isEnabled());
            });
            await(() -> find(workbench, JButton.class, button -> "Save domain…".equals(button.getText()))
                    .isEnabled(), "build did not finish");
            var manifest = storage.buildManifestFile(model.name()).toPath();
            String report = Files.isRegularFile(manifest) ? Files.readString(manifest) : "No build manifest was written";
            SwingUtilities.invokeAndWait(() -> {
                var messages = descendants(workbench).filter(JLabel.class::isInstance).map(JLabel.class::cast)
                        .map(JLabel::getText).filter(java.util.Objects::nonNull)
                        .filter(message -> message.contains("Results") || message.contains("Process failed"))
                        .toList();
                assertTrue(actionNode(workbench, "Instances (3)") != null, () -> report + "\n" + messages);
                // A build refreshes the one instances view without opening its window, as
                // ModelBuilder does; its result offers Show instances instead of a copy.
                assertEquals(List.of(false), shown.stream().map(UnifiedWorkbenchPanel.InstancesShown::reveal).toList());
                selectAction(workbench, "Instances (3)");
                assertFalse(descendants(workbench).anyMatch(QueryObjectResultPanel.class::isInstance));
                find(workbench, JButton.class, button -> "Show instances".equals(button.getText())).doClick();
                assertEquals(List.of(false, true), shown.stream().map(UnifiedWorkbenchPanel.InstancesShown::reveal).toList());
            });
            var built = workbench.instancesView();
            await(() -> built.displayedObjectsByType().containsKey(output.className()),
                    "built instance cards did not render");
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(List.of("Q1", "Q2"), built.displayedObjectsByType().get(output.className()).stream()
                        .map(objectview.Viewable::getIdentifier).sorted().toList());
                var selection = find(workbench, SingleRootClassModelPanel.class, unused -> true).selectedUserObject();
                assertEquals(output.className(), ((GeneratedClassModel) selection).className());
                assertFalse(selection == working, "the navigator is over the refreshed working model");
                assertFalse(descendants(workbench).filter(Component::isVisible)
                        .filter(JButton.class::isInstance).map(JButton.class::cast)
                        .anyMatch(button -> button.getText().startsWith("Build already saved selected")));
            });
            // Saving again uses the re-opened version and cannot overwrite the publication.
            workbench.saveProject();
            var builtModel = new GeneratedProjectModelStore().load(modelFile.toFile());
            assertEquals(List.of("Q1", "Q2"), ((PopulationSelection) builtModel.findSelection(population.name()))
                    .instanceQids().stream().sorted().toList());
        } finally {
            SwingUtilities.invokeAndWait(panel.get()::close);
        }
    }

    /**
     * Load instances reads the saved snapshot again through the saved model and curation:
     * the reading opening and a build's last step make, without the build. A field saved
     * since the last build then belongs to the open project, so its recipes can run.
     */
    @Test void loadInstancesReadsTheSavedInstancesThroughTheSavedModelWithoutABuild() throws Exception {
        var storage = DomainStorage.in(root.toFile());
        var model = new GeneratedProjectModel();
        model.name("Positions");
        var position = new GeneratedClassModel("Position");
        position.seedQids().addAll(List.of("Q1", "Q2"));
        position.addField("holderCount", FieldType.NUMBER, FieldCardinality.SINGLE);
        model.rootClass(position);
        var first = new WikidataDynamicObject("Q1", "First position");
        first.type(position.className());
        first.put("holderCount", 5);
        var second = new WikidataDynamicObject("Q2", "Second position");
        second.type(position.className());
        var run = new ProjectSave.Run(List.of(first, second), List.of(), null, model);
        ProjectSave.plan(new ProjectSave.Input(model, run, null, List.of()), storage).write();
        Path modelFile = storage.modelFile(model.name()).toPath();
        Path snapshot = storage.snapshotFile(model.name()).toPath();
        String savedInstances = Files.readString(snapshot);
        var domain = DomainCatalog.modelBacked(storage).getFirst().opener().open();

        // The model gains a field after the instances were built, as a Save elsewhere does.
        var saved = new GeneratedProjectModelStore().load(modelFile.toFile());
        saved.findClass(position.className()).addField("rank", FieldType.TEXT, FieldCardinality.SINGLE);
        new GeneratedProjectModelStore().save(saved, modelFile.toFile());

        var panel = new AtomicReference<UnifiedWorkbenchPanel>();
        var shown = new java.util.concurrent.CopyOnWriteArrayList<UnifiedWorkbenchPanel.InstancesShown>();
        SwingUtilities.invokeAndWait(() -> {
            var workbench = new UnifiedWorkbenchPanel(domain);
            panel.set(workbench);
            workbench.onInstancesShown(shown::add);
            assertFalse(hasField(workbench, "rank"), "the open project predates the field");
            selectAction(workbench, "Load instances");
            var curation = ManualCuration.fileForSnapshot(snapshot.toFile());
            var autofix = CorrectionsSidecar.beside(snapshot.toFile(), ".autofix.json");
            String readiness = loadStatus(workbench);
            assertTrue(readiness.startsWith("Reads " + modelFile), readiness);
            assertTrue(readiness.contains(snapshot.toString()), readiness);
            assertTrue(readiness.contains(curation.getPath()), readiness);
            assertTrue(readiness.contains(autofix.getPath()), readiness);
            find(workbench, JButton.class, button -> "Load instances".equals(button.getText())).doClick();
            find(workbench, JLabel.class, label -> "Loading instances…".equals(label.getText()));
            assertTrue(loadStatus(workbench).startsWith("Loading " + modelFile));
            assertTrue(loadStatus(workbench).contains(curation.getPath()));
            assertFalse(find(workbench, JButton.class, b -> "Cancel preview".equals(b.getText())).isEnabled());
        });
        try {
            var workbench = panel.get();
            await(() -> loadStatus(workbench).startsWith("2 saved instances loaded"), "instances were not loaded");
            var cards = workbench.instancesView();
            var previousContent = new AtomicReference<Component>();
            await(() -> cards.displayedObjectsByType().containsKey("Position"),
                    "the only result tab did not render the instances");
            SwingUtilities.invokeAndWait(() -> {
                assertNull(actionNode(workbench, "Instances (2)"));
                assertFalse(SwingUtilities.isDescendingFrom(cards, workbench));
                assertEquals(List.of(new UnifiedWorkbenchPanel.InstancesShown(
                        "Saved instances (2) — " + snapshot.getFileName(), true)), shown,
                        "Load instances opens the instances window, titled with what it holds");
                assertEquals("Project", actionNode(workbench, "Load instances").getParent().toString());
                assertEquals("Run graph", actionNode(workbench, "Review graph decisions").getParent().toString());
                assertEquals(List.of("Q1", "Q2"), cards.displayedObjectsByType().get("Position").stream()
                        .map(objectview.Viewable::getIdentifier).sorted().toList());
                assertTrue(hasField(workbench, "rank"), "the loaded project is read through the saved model");
                assertEquals("2 saved instances loaded from " + snapshot + ".", loadStatus(workbench));

                // Leaving and reselecting Load instances used to show the pre-load text, as
                // if nothing were loaded; the loaded state stays, and the window reopens.
                selectAction(workbench, "Build project");
                selectAction(workbench, "Load instances");
                assertTrue(loadStatus(workbench).startsWith("2 saved instances loaded from "
                        + snapshot + ". Show instances opens them"), loadStatus(workbench));
                shown.clear();
                previousContent.set(((java.awt.Container) cards.getComponent(0)).getComponent(0));
                find(workbench, JButton.class, b -> "load.instances.show".equals(b.getName())).doClick();
                assertEquals(List.of(new UnifiedWorkbenchPanel.InstancesShown(
                        "Saved instances (2) — " + snapshot.getFileName(), true)), shown);
            });
            SwingUtilities.invokeAndWait(() -> assertSame(previousContent.get(),
                    ((java.awt.Container) cards.getComponent(0)).getComponent(0)));
            assertEquals(savedInstances, Files.readString(snapshot), "loading writes nothing");
        } finally {
            SwingUtilities.invokeAndWait(panel.get()::close);
        }
    }

    @Test void cancellingLoadInstancesUnlocksTheOpenProjectAndAllowsRetry() throws Exception {
        var opened = open();
        var readStarted = new CountDownLatch(1);
        var readAllowed = new CountDownLatch(1);
        var session = new SwingQuerySession(new QueryContext() {
            @Override public QueryContext withRecorder(WorkflowRecorder recorder) {
                readStarted.countDown();
                try {
                    readAllowed.await();
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                }
                return super.withRecorder(recorder);
            }
        });
        var panel = new AtomicReference<UnifiedWorkbenchPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var workbench = new UnifiedWorkbenchPanel(opened.domain(), session);
            panel.set(workbench);
            startLoadingInstances(workbench);
            assertTrue(find(workbench, JButton.class, button -> "Cancel load".equals(button.getText())).isEnabled());
        });
        try {
            var workbench = panel.get();
            assertTrue(readStarted.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(session.runner()::cancel);
            await(() -> find(workbench, JButton.class, b -> "Load instances".equals(b.getText())).isEnabled(),
                    "cancellation did not unlock the workbench");
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(loadStatus(workbench).startsWith("Cancelled loading "));
                assertTrue(find(workbench, JButton.class, b -> "Save domain…".equals(b.getText())).isEnabled());
                assertFalse(find(workbench, JButton.class, b -> "Cancel load".equals(b.getText())).isEnabled());
                assertTrue(actionNode(workbench, "Instances (1)") == null, "the cancelled read published no result");
                assertTrue(hasField(workbench, "symbol"));
            });
            readAllowed.countDown();
            SwingUtilities.invokeAndWait(() -> startLoadingInstances(workbench));
            await(() -> loadStatus(workbench).startsWith("1 saved instances loaded"), "the retry did not load instances");
        } finally {
            readAllowed.countDown();
            SwingUtilities.invokeAndWait(panel.get()::close);
        }
    }

    @Test void aMissingOrUnreadableModelKeepsTheOpenProjectAndReportsTheFile() throws Exception {
        var opened = open();
        var panel = new AtomicReference<UnifiedWorkbenchPanel>();
        var originalTree = new AtomicReference<SingleRootClassModelPanel>();
        Files.delete(opened.modelPath());
        SwingUtilities.invokeAndWait(() -> {
            var workbench = new UnifiedWorkbenchPanel(opened.domain());
            panel.set(workbench);
            originalTree.set(find(workbench, SingleRootClassModelPanel.class, unused -> true));
            startLoadingInstances(workbench);
            assertEquals("No saved model at " + opened.modelPath() + ".", loadStatus(workbench));
            assertTrue(find(workbench, JButton.class, b -> "Load instances".equals(b.getText())).isEnabled());
        });
        try {
            var workbench = panel.get();
            Files.writeString(opened.modelPath(), "{invalid");
            SwingUtilities.invokeAndWait(() -> startLoadingInstances(workbench));
            await(() -> loadStatus(workbench).startsWith("Could not load "), "the unreadable model was not reported");
            SwingUtilities.invokeAndWait(() -> {
                assertSame(originalTree.get(), find(workbench, SingleRootClassModelPanel.class, unused -> true));
                assertTrue(hasField(workbench, "symbol"));
                assertTrue(find(workbench, JButton.class, b -> "Save domain…".equals(b.getText())).isEnabled());
            });
            Files.writeString(opened.modelPath(), opened.savedModel());
            SwingUtilities.invokeAndWait(() -> startLoadingInstances(workbench));
            await(() -> loadStatus(workbench).startsWith("1 saved instances loaded"), "load did not recover after restoring the model");
        } finally {
            SwingUtilities.invokeAndWait(panel.get()::close);
        }
    }

    @Test void reloadingAnExternallySavedProjectInvalidatesItsOpenBuildPlan() throws Exception {
        var opened = open();
        var storage = DomainStorage.in(root.toFile());
        var model = opened.modelPath().toFile();
        var snapshot = opened.domain().capability(ProjectBacking.class).snapshotFile();
        var store = new GeneratedProjectModelStore();
        var saved = store.load(model);
        saved.buildOperations(List.of(new BuildOperation(BuildOperation.Kind.SAVE_PROJECT_RESULT, "")));
        store.save(saved, model);
        var current = DomainCatalog.open(snapshot, model, storage);
        var panel = new AtomicReference<UnifiedWorkbenchPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var workbench = new UnifiedWorkbenchPanel(current);
            panel.set(workbench);
            selectAction(workbench, "Build project");
            find(workbench, JButton.class, b -> "Plan build".equals(b.getText())).doClick();
            find(workbench, JButton.class, b -> "Execute".equals(b.getText()));
        });
        try {
            var workbench = panel.get();
            saved.buildOperations(List.of(new BuildOperation(BuildOperation.Kind.GENERATE_PROJECT, ""),
                    new BuildOperation(BuildOperation.Kind.SAVE_PROJECT_RESULT, "")));
            store.save(saved, model);
            SwingUtilities.invokeAndWait(() -> startLoadingInstances(workbench));
            await(() -> loadStatus(workbench).startsWith("1 saved instances loaded"), "the saved version was not reloaded");
            SwingUtilities.invokeAndWait(() -> {
                selectAction(workbench, "Build project");
                assertFalse(descendants(workbench).filter(JButton.class::isInstance).map(JButton.class::cast)
                        .anyMatch(button -> "Execute".equals(button.getText())));
                find(workbench, JButton.class, b -> "Plan build".equals(b.getText())).doClick();
                find(workbench, JButton.class, b -> b.getText().contains("1 · Generate project"));
            });
            assertFalse(storage.buildManifestFile(saved.name()).isFile(), "reloading and replanning do not build");
        } finally {
            SwingUtilities.invokeAndWait(panel.get()::close);
        }
    }

    private static void startLoadingInstances(UnifiedWorkbenchPanel workbench) {
        selectAction(workbench, "Load instances");
        find(workbench, JButton.class, button -> "Load instances".equals(button.getText())).doClick();
    }

    private static String loadStatus(UnifiedWorkbenchPanel workbench) {
        return find(workbench, JTextArea.class, status -> "load.instances.status".equals(status.getName())).getText();
    }

    @Test void theUnifiedNavigatorMountsTheSharedDiscoveryActionsWithoutRunningThem() throws Exception {
        var opened = open();
        SwingUtilities.invokeAndWait(() -> {
            try (var workbench = new UnifiedWorkbenchPanel(opened.domain())) {
                Map.of(
                        "Analyze retained articles", "Wikipedia",
                        "Fetch selected articles", "Wikipedia",
                        "Fetch structured depictions", "Commons",
                        "Read article views", "Wikipedia pageviews",
                        "Fetch statements and qualifiers", "Wikidata",
                        "Load the current configured field", "Wikidata")
                        .forEach((action, source) -> {
                            assertEquals(source, actionNode(workbench, action).getParent().toString());
                            selectAction(workbench, action);
                        });
                assertNull(actionNode(workbench, "Datasources"));
                assertTrue(opened.domain().capability(ProjectBacking.class).unsavedModelChanges().isEmpty());
            }
        });
    }

    /** The configuration editors ask for over 600 px; on a laptop-height window that used
     *  to win the split and squeeze Load instances and Build out of sight. */
    @Test void theActionsKeepTheirRoomInAShortWindow() throws Exception {
        var opened = open();
        SwingUtilities.invokeAndWait(() -> {
            try (var workbench = new UnifiedWorkbenchPanel(opened.domain())) {
                workbench.setSize(900, 600);
                layoutTree(workbench);
                var actions = find(workbench, WorkbenchActionResultNavigator.class, unused -> true);
                assertTrue(actions.getHeight() >= UnifiedWorkbenchPanel.ACTIONS_MINIMUM_HEIGHT,
                        "actions are " + actions.getHeight() + " px tall");
            }
        });
    }

    @Test void narrowingTheWorkbenchKeepsEveryWrappedFooterButtonInsideItsBounds() throws Exception {
        var opened = open();
        opened.domain().capability(ProjectBacking.class).recordModelChange("Build steps");
        SwingUtilities.invokeAndWait(() -> {
            try (var workbench = new UnifiedWorkbenchPanel(opened.domain())) {
                var logs = find(workbench, JButton.class, button -> "Query logs…".equals(button.getText()));
                var footer = logs.getParent();
                var save = find(footer, JButton.class, button -> "Save domain…".equals(button.getText()));
                int singleRowWidth = footer.getPreferredSize().width;

                workbench.setSize(singleRowWidth + 40, 600);
                layoutTree(workbench);
                int singleRowHeight = footer.getHeight();

                // Make room for only half of Save, well beyond the layout's inter-button gap.
                workbench.setSize(singleRowWidth - save.getWidth() / 2, 600);
                layoutTree(workbench);

                assertTrue(save.getY() >= logs.getY() + logs.getHeight(), "the Save button must wrap");
                assertTrue(footer.getHeight() > singleRowHeight, "the footer must grow for the second row");
                var visible = new Rectangle(0, 0, footer.getWidth(), footer.getHeight());
                for (JButton button : List.of(logs, save)) {
                    assertTrue(visible.contains(button.getBounds()), button.getText() + " is clipped");
                }
            }
        });
    }

    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) layoutTree(nested);
        }
    }

    @Test void loadInstancesIsRefusedWhileChangesAreUnsaved() throws Exception {
        var opened = open();
        var project = opened.domain().capability(ProjectBacking.class);
        project.recordModelChange("Field configuration Person.birthDate");
        var panel = new AtomicReference<UnifiedWorkbenchPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var workbench = new UnifiedWorkbenchPanel(opened.domain());
            panel.set(workbench);
            selectAction(workbench, "Load instances");
            find(workbench, JButton.class, button -> "Load instances".equals(button.getText())).doClick();
            assertTrue(loadStatus(workbench).contains("before loading instances."));
            assertTrue(loadStatus(workbench).contains(project.modelFile().getPath()));
            assertTrue(actionNode(workbench, "Instances (" + opened.domain().memberRoots().size() + ")") == null,
                    "an unsaved change is never replaced by the files");
        });
        SwingUtilities.invokeAndWait(panel.get()::close);
    }

    private static boolean hasField(UnifiedWorkbenchPanel workbench, String field) {
        var tree = find(workbench, SingleRootClassModelPanel.class, unused -> true);
        var nodes = descendants(tree).filter(javax.swing.JTree.class::isInstance)
                .map(javax.swing.JTree.class::cast).findFirst().orElseThrow();
        var rootNode = (DefaultMutableTreeNode) nodes.getModel().getRoot();
        return java.util.Collections.list(rootNode.depthFirstEnumeration()).stream()
                .map(node -> ((DefaultMutableTreeNode) node).getUserObject())
                .anyMatch(value -> value instanceof GeneratedFieldModel f && field.equals(f.name()));
    }

    private static DefaultMutableTreeNode actionNode(UnifiedWorkbenchPanel workbench, String caption) {
        var tree = find(workbench, WorkbenchActionResultNavigator.class, unused -> true).tree();
        var rootNode = (DefaultMutableTreeNode) tree.getModel().getRoot();
        return java.util.Collections.list(rootNode.depthFirstEnumeration()).stream()
                .map(DefaultMutableTreeNode.class::cast)
                .filter(node -> caption.equals(node.toString())).findFirst().orElse(null);
    }

    private static void selectAction(UnifiedWorkbenchPanel workbench, String caption) {
        var tree = find(workbench, WorkbenchActionResultNavigator.class, unused -> true).tree();
        var node = actionNode(workbench, caption);
        assertTrue(node != null, "missing action or result " + caption);
        tree.setSelectionPath(new TreePath(node.getPath()));
    }

    @Test void theSharedBuildEditorSavesTheOrderUsedByTheNextPlan() throws Exception {
        var opened = open();
        var project = opened.domain().capability(ProjectBacking.class);
        var storage = DomainStorage.in(root.toFile());
        var panel = new AtomicReference<UnifiedWorkbenchPanel>();
        var savedOrder = new AtomicReference<List<String>>();
        SwingUtilities.invokeAndWait(() -> {
            var workbench = new UnifiedWorkbenchPanel(opened.domain());
            panel.set(workbench);
            selectAction(workbench, "Build project");
            find(workbench, JButton.class, button -> "Plan build".equals(button.getText())).doClick();
            find(workbench, JLabel.class, label -> label.getText().contains("No build steps")
                    && label.getText().contains("Edit build steps"));
            find(workbench, JButton.class, button -> "Edit build steps".equals(button.getText())).doClick();

            var constructs = find(workbench, SingleRootClassModelPanel.class, unused -> true);
            assertSame(opened.working(), constructs.selectedUserObject());
            var kind = find(workbench, JComboBox.class, box -> "project.kind".equals(box.getName()));
            assertFalse(kind.isEnabled());
            var build = find(workbench, JPanel.class, candidate -> "project.build".equals(candidate.getName()));
            var options = find(build, JComboBox.class, unused -> true);
            assertTrue(options.isEnabled());
            for (var operation : List.of(BuildOperation.Kind.SAVE_PROJECT_RESULT,
                    BuildOperation.Kind.GENERATE_PROJECT)) {
                for (int i = 0; i < options.getItemCount(); i++) {
                    if (options.getItemAt(i) instanceof BuildOperation step && step.kind() == operation) {
                        options.setSelectedIndex(i);
                        break;
                    }
                }
                find(build, JButton.class, button -> "Add".equals(button.getText())).doClick();
            }
            var rows = find(build, JList.class, unused -> true);
            var beforeMove = opened.working().buildOperations().stream().map(BuildOperation::declarationId).toList();
            var changes = project.unsavedModelChanges();
            rows.setSelectedIndex(1);
            assertEquals(changes, project.unsavedModelChanges(), "selecting a build row is inspection");
            find(build, JButton.class, button -> "Up".equals(button.getText())).doClick();
            savedOrder.set(beforeMove.reversed());
            assertEquals(savedOrder.get(), opened.working().buildOperations().stream()
                    .map(BuildOperation::declarationId).toList());
            assertEquals(List.of("Build steps"), project.unsavedModelChanges());
            assertFalse(workbench.hasUnappliedEdits(), "build row commands update the working model");
            find(workbench, JButton.class, button -> "Plan build".equals(button.getText())).doClick();
            find(workbench, JLabel.class, label -> label.getText().contains("before building."));
            assertFalse(workbench.saving());
        });
        try {
            assertEquals(opened.savedModel(), Files.readString(opened.modelPath()));
            var workbench = panel.get();
            SwingUtilities.invokeAndWait(() -> {
                workbench.persistenceConfirmation(message -> message.contains(opened.modelPath().toString()));
                workbench.showSaveSuccess(false);
                find(workbench, JButton.class, button -> "Save domain…".equals(button.getText())).doClick();
            });
            awaitSaved(workbench);
            var saved = new GeneratedProjectModelStore().load(opened.modelPath().toFile());
            assertEquals(savedOrder.get(), saved.buildOperations().stream().map(BuildOperation::declarationId).toList());
            SwingUtilities.invokeAndWait(() -> {
                find(workbench, JLabel.class, label -> label.getText().contains("Plan the saved build of")
                        && label.getText().contains(opened.modelPath().toString()));
                find(workbench, JButton.class, button -> "Plan build".equals(button.getText())).doClick();
                find(workbench, JButton.class, button -> button.getText().contains("1 · Generate project"));
                find(workbench, JButton.class, button -> button.getText().contains("2 · Save project"));
            });
            assertFalse(storage.buildManifestFile(saved.name()).exists(), "planning executes no build steps");
        } finally {
            SwingUtilities.invokeAndWait(panel.get()::close);
        }
    }

    @Test void aSaveOutsideTheWorkbenchAfterPlanningRefusesTheBuild() throws Exception {
        var opened = open();
        var storage = DomainStorage.in(root.toFile());
        var modelFile = opened.modelPath().toFile();
        var snapshot = storage.snapshotFile(opened.working().name());
        var store = new GeneratedProjectModelStore();
        var saved = store.load(modelFile);
        saved.buildOperations(List.of(new BuildOperation(BuildOperation.Kind.SAVE_PROJECT_RESULT, "")));
        store.save(saved, modelFile);
        var domain = DomainCatalog.open(snapshot, modelFile, storage);
        var panel = new AtomicReference<UnifiedWorkbenchPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var workbench = new UnifiedWorkbenchPanel(domain);
            panel.set(workbench);
            selectAction(workbench, "Build project");
            find(workbench, JButton.class, button -> "Plan build".equals(button.getText())).doClick();
        });
        try {
            String previousSnapshot = Files.readString(snapshot.toPath());
            named(saved, "symbol").mapping().propertyPid("Rendered.External");
            store.save(saved, modelFile);
            String externalSave = Files.readString(modelFile.toPath());

            SwingUtilities.invokeAndWait(() -> find(panel.get(), JButton.class,
                    button -> "Execute".equals(button.getText())).doClick());
            await(() -> descendants(panel.get()).filter(JLabel.class::isInstance).map(JLabel.class::cast)
                    .anyMatch(label -> label.getText() != null && label.getText().contains("since this copy")),
                    "the stale build did not report the external save");
            assertFalse(storage.buildManifestFile(saved.name()).isFile());
            assertEquals(externalSave, Files.readString(modelFile.toPath()));
            assertEquals(previousSnapshot, Files.readString(snapshot.toPath()));
        } finally {
            SwingUtilities.invokeAndWait(panel.get()::close);
        }
    }

    /** An opened two-field project, with its saved model text for comparison. */
    private record Opened(domain.DomainModel domain, GeneratedProjectModel working,
            Path modelPath, String savedModel) { }

    private Opened open() throws Exception {
        var storage = DomainStorage.in(root.toFile());
        var model = new GeneratedProjectModel();
        model.name("Egyptian Gods");
        var deity = new GeneratedClassModel("EgyptianDeity");
        deity.seedQids().add("Q47534");
        var symbol = deity.addField("symbol", FieldType.TEXT, FieldCardinality.SINGLE);
        symbol.mapping().sourceType(FieldSourceType.WIKIPEDIA_INFOBOX);
        symbol.mapping().propertyPid("Rendered.Symbol");
        var consort = deity.addField("consort", FieldType.TEXT, FieldCardinality.SINGLE);
        consort.mapping().sourceType(FieldSourceType.WIKIPEDIA_INFOBOX);
        consort.mapping().propertyPid("Rendered.Consort");
        model.rootClass(deity);
        var anubis = new WikidataDynamicObject("Q47534", "Anubis");
        anubis.type("EgyptianDeity");
        var run = new ProjectSave.Run(List.of(anubis), List.of(), null, model);
        ProjectSave.plan(new ProjectSave.Input(model, run, null, List.of()), storage).write();
        Path modelPath = storage.modelFile(model.name()).toPath();
        var opened = DomainCatalog.modelBacked(storage).getFirst().opener().open();
        return new Opened(opened, opened.capability(ProjectBacking.class).projectModel(),
                modelPath, Files.readString(modelPath));
    }

    private static GeneratedFieldModel named(GeneratedProjectModel model, String name) {
        return model.rootClass().fields().stream().filter(field -> field.name().equals(name))
                .findFirst().orElseThrow();
    }

    /** Selects the field and types a new property into its primary source. */
    private static void edit(UnifiedWorkbenchPanel workbench, GeneratedFieldModel field, String pid) {
        find(workbench, SingleRootClassModelPanel.class, unused -> true).selectField(field);
        find(workbench, JTextField.class, input -> "field.source.property".equals(input.getName()))
                .setText(pid);
    }

    private static String property(UnifiedWorkbenchPanel workbench) {
        return find(workbench, JTextField.class,
                input -> "field.source.property".equals(input.getName())).getText();
    }

    @Test void leavingAFieldWithUnappliedEditsAsksApplyDiscardOrStay() throws Exception {
        for (var answer : process.swing.Dialogs.UnappliedEdits.values()) {
            var opened = open();
            var symbol = named(opened.working(), "symbol");
            var consort = named(opened.working(), "consort");
            SwingUtilities.invokeAndWait(() -> {
                try (var workbench = new UnifiedWorkbenchPanel(opened.domain())) {
                    List<String> asked = new java.util.ArrayList<>();
                    workbench.unappliedDecision((construct, action) -> {
                        asked.add(construct + " / " + action);
                        return answer;
                    });
                    edit(workbench, symbol, "Rendered.Parents");
                    find(workbench, SingleRootClassModelPanel.class, unused -> true).selectField(consort);

                    assertEquals(List.of("Field EgyptianDeity.symbol / opening another construct"),
                            asked, answer.name());
                    switch (answer) {
                        case STAY -> {
                            assertEquals("Rendered.Parents", property(workbench), "Stay keeps the edit");
                            assertEquals("Rendered.Symbol", symbol.mapping().propertyPid());
                        }
                        case DISCARD -> {
                            assertEquals("Rendered.Consort", property(workbench));
                            assertEquals("Rendered.Symbol", symbol.mapping().propertyPid());
                        }
                        case APPLY -> {
                            assertEquals("Rendered.Consort", property(workbench));
                            assertEquals("Rendered.Parents", symbol.mapping().propertyPid());
                        }
                    }
                }
            });
        }
    }

    @Test void saveWithOnlyUnappliedEditsResolvesTheDraftOnlyAfterTheFileIsConfirmed()
            throws Exception {
        var opened = open();
        var symbol = named(opened.working(), "symbol");
        var panel = new AtomicReference<UnifiedWorkbenchPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var workbench = new UnifiedWorkbenchPanel(opened.domain());
            panel.set(workbench);
            workbench.showSaveSuccess(false);
            edit(workbench, symbol, "Rendered.Parents");
            var save = find(workbench, JButton.class, button -> "Save domain…".equals(button.getText()));
            assertTrue(save.isEnabled(), "a draft alone is a change Save can resolve");

            workbench.choice((title, message, options, defaultOption) -> {
                assertEquals(List.of("Apply and save", "Discard draft and save applied changes",
                        "Stay"), List.of(options));
                assertEquals(2, defaultOption);
                return defaultOption;
            });
            save.doClick();
            assertEquals("Rendered.Parents", property(workbench), "Stay keeps the draft");
            assertEquals("Rendered.Symbol", symbol.mapping().propertyPid());

            workbench.choice((title, message, options, defaultOption) -> 0);
            workbench.persistenceConfirmation(message -> false);
            save.doClick();
            assertEquals("Rendered.Parents", property(workbench),
                    "cancelling the file confirmation keeps the draft");
            assertEquals("Rendered.Symbol", symbol.mapping().propertyPid(), "and applies nothing");

            workbench.persistenceConfirmation(message -> {
                assertTrue(message.contains(opened.modelPath().toString()), message);
                return true;
            });
            save.doClick();
            assertEquals("Rendered.Parents", symbol.mapping().propertyPid());
        });
        awaitSaved(panel.get());
        assertTrue(Files.readString(opened.modelPath()).contains("Rendered.Parents"));
        SwingUtilities.invokeAndWait(panel.get()::close);

        var reopened = DomainCatalog.modelBacked(DomainStorage.in(root.toFile())).getFirst()
                .opener().open();
        assertEquals("Rendered.Parents", named(reopened.capability(ProjectBacking.class)
                .projectModel(), "symbol").mapping().propertyPid(), "reopening shows the edit");
    }

    @Test void discardingTheDraftSavesOnlyTheAppliedChanges() throws Exception {
        var opened = open();
        var symbol = named(opened.working(), "symbol");
        var consort = named(opened.working(), "consort");
        var panel = new AtomicReference<UnifiedWorkbenchPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var workbench = new UnifiedWorkbenchPanel(opened.domain());
            panel.set(workbench);
            workbench.showSaveSuccess(false);
            workbench.persistenceConfirmation(message -> true);
            edit(workbench, consort, "Rendered.Spouse");
            find(workbench, JButton.class, button -> "Apply field source".equals(button.getText()))
                    .doClick();
            workbench.unappliedDecision((construct, action) -> process.swing.Dialogs.UnappliedEdits.STAY);
            edit(workbench, consort, "Rendered.Children");
            workbench.choice((title, message, options, defaultOption) -> 1);
            find(workbench, JButton.class, button -> "Save domain…".equals(button.getText())).doClick();
            assertEquals("Rendered.Spouse", property(workbench), "the draft is discarded");
        });
        awaitSaved(panel.get());
        String saved = Files.readString(opened.modelPath());
        assertTrue(saved.contains("Rendered.Spouse"), "the applied change is saved");
        assertFalse(saved.contains("Rendered.Children"));
        assertEquals("Rendered.Symbol", symbol.mapping().propertyPid());
        SwingUtilities.invokeAndWait(panel.get()::close);
    }

    @Test void closeOffersCompleteActionsAndStayIsTheDefault() throws Exception {
        // Stay: nothing applied, nothing closed.
        var stay = open();
        SwingUtilities.invokeAndWait(() -> {
            try (var workbench = new UnifiedWorkbenchPanel(stay.domain())) {
                edit(workbench, named(stay.working(), "symbol"), "Rendered.Parents");
                boolean[] closed = {false};
                workbench.choice((title, message, options, defaultOption) -> {
                    assertEquals(List.of("Apply, save and close", "Discard changes and close", "Stay"),
                            List.of(options));
                    assertEquals(2, defaultOption);
                    return defaultOption;
                });
                workbench.requestClose(() -> closed[0] = true);
                assertFalse(closed[0]);
                assertEquals("Rendered.Parents", property(workbench));
            }
        });
        assertEquals(stay.savedModel(), Files.readString(stay.modelPath()));

        // Discard: closes once, writes nothing.
        var discard = open();
        SwingUtilities.invokeAndWait(() -> {
            try (var workbench = new UnifiedWorkbenchPanel(discard.domain())) {
                edit(workbench, named(discard.working(), "symbol"), "Rendered.Parents");
                int[] asked = {0};
                boolean[] closed = {false};
                workbench.choice((title, message, options, defaultOption) -> {
                    asked[0]++;
                    return 1;
                });
                workbench.requestClose(() -> closed[0] = true);
                assertTrue(closed[0]);
                assertEquals(1, asked[0], "one decision, not Apply and then discard");
            }
        });
        assertEquals(discard.savedModel(), Files.readString(discard.modelPath()));

        // Apply, save and close.
        var apply = open();
        var panel = new AtomicReference<UnifiedWorkbenchPanel>();
        boolean[] closed = {false};
        SwingUtilities.invokeAndWait(() -> {
            var workbench = new UnifiedWorkbenchPanel(apply.domain());
            panel.set(workbench);
            edit(workbench, named(apply.working(), "symbol"), "Rendered.Parents");
            workbench.choice((title, message, options, defaultOption) -> 0);
            workbench.requestClose(() -> closed[0] = true);
        });
        awaitSaved(panel.get());
        assertTrue(closed[0]);
        assertTrue(Files.readString(apply.modelPath()).contains("Rendered.Parents"));
        SwingUtilities.invokeAndWait(panel.get()::close);

        // Only applied, unsaved changes: the same shape of question.
        var applied = open();
        SwingUtilities.invokeAndWait(() -> {
            try (var workbench = new UnifiedWorkbenchPanel(applied.domain())) {
                edit(workbench, named(applied.working(), "symbol"), "Rendered.Parents");
                find(workbench, JButton.class, button -> "Apply field source".equals(button.getText()))
                        .doClick();
                workbench.choice((title, message, options, defaultOption) -> {
                    assertEquals(List.of("Save and close", "Discard changes and close", "Stay"),
                            List.of(options));
                    return 2;
                });
                workbench.requestClose(() -> { throw new AssertionError("Stay closed the window"); });
            }
        });
    }

    /** Close resolves a draft and applied unsaved changes together; when the write
     *  fails (here: another writer changed the file), the workbench stays open and the
     *  applied changes stay in memory. */
    @Test void aFailedSaveDuringCloseKeepsTheWorkbenchOpen() throws Exception {
        var opened = open();
        var symbol = named(opened.working(), "symbol");
        var consort = named(opened.working(), "consort");
        var panel = new AtomicReference<UnifiedWorkbenchPanel>();
        List<String> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
        boolean[] closed = {false};
        SwingUtilities.invokeAndWait(() -> {
            var workbench = new UnifiedWorkbenchPanel(opened.domain());
            panel.set(workbench);
            workbench.saveFailure(failures::add);
            edit(workbench, consort, "Rendered.Spouse");
            find(workbench, JButton.class, button -> "Apply field source".equals(button.getText()))
                    .doClick();
            workbench.unappliedDecision((construct, action) -> process.swing.Dialogs.UnappliedEdits.STAY);
            edit(workbench, consort, "Rendered.Children");
        });
        Files.writeString(opened.modelPath(), opened.savedModel() + " ");
        SwingUtilities.invokeAndWait(() -> {
            panel.get().choice((title, message, options, defaultOption) -> {
                assertTrue(message.contains("applied changes are not saved"), message);
                return 0;
            });
            panel.get().requestClose(() -> closed[0] = true);
        });
        awaitSaved(panel.get());
        assertFalse(closed[0], "a failed save keeps the workbench open");
        assertEquals(1, failures.size(), failures.toString());
        assertEquals("Rendered.Children", consort.mapping().propertyPid(),
                "the draft was applied and stays in memory");
        assertEquals("Rendered.Symbol", symbol.mapping().propertyPid());
        SwingUtilities.invokeAndWait(panel.get()::close);
    }

    @Test void aRefusedApplyKeepsTheWindowAndTheEdits() throws Exception {
        var opened = open();
        var symbol = named(opened.working(), "symbol");
        SwingUtilities.invokeAndWait(() -> {
            try (var workbench = new UnifiedWorkbenchPanel(opened.domain())) {
                find(workbench, SingleRootClassModelPanel.class, unused -> true).selectField(symbol);
                // A datasource operation that is not chosen cannot be applied.
                @SuppressWarnings("unchecked")
                JComboBox<Object> from = find(workbench, JComboBox.class, combo -> {
                    for (int i = 0; i < combo.getItemCount(); i++) {
                        if (combo.getItemAt(i) == FieldSourceType.DATASOURCE) return true;
                    }
                    return false;
                });
                from.setSelectedItem(FieldSourceType.DATASOURCE);
                assertTrue(workbench.hasUnappliedEdits());
                boolean[] closed = {false};
                workbench.unappliedDecision((construct, action) ->
                        process.swing.Dialogs.UnappliedEdits.APPLY);
                find(workbench, SingleRootClassModelPanel.class, unused -> true)
                        .selectField(named(opened.working(), "consort"));
                assertEquals(FieldSourceType.DATASOURCE, from.getSelectedItem(),
                        "a refused Apply keeps the selection and the draft");

                workbench.choice((title, message, options, defaultOption) -> 0);
                workbench.persistenceConfirmation(message -> true);
                find(workbench, JButton.class, button -> "Save domain…".equals(button.getText()))
                        .doClick();
                assertFalse(workbench.saving(), "a refused Apply saves nothing");
                assertEquals(FieldSourceType.DATASOURCE, from.getSelectedItem());

                workbench.requestClose(() -> closed[0] = true);
                assertFalse(closed[0], "a refused Apply keeps the window open");
                assertEquals(FieldSourceType.DATASOURCE, from.getSelectedItem(), "and the edit");
                assertEquals(FieldSourceType.WIKIPEDIA_INFOBOX, symbol.mapping().sourceType());
            }
        });
        assertEquals(opened.savedModel(), Files.readString(opened.modelPath()));
    }

    private static void awaitSaved(UnifiedWorkbenchPanel workbench) throws Exception {
        await(() -> !workbench.saving(), "save did not finish");
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (true) {
            boolean[] done = {false};
            SwingUtilities.invokeAndWait(() -> done[0] = condition.getAsBoolean());
            if (done[0]) return;
            if (System.currentTimeMillis() > deadline) throw new AssertionError(message);
            Thread.sleep(50);
        }
    }

    private static GeneratedFieldModel symbol(GeneratedProjectModel model) {
        return model.rootClass().fields().stream().filter(field -> field.name().equals("symbol"))
                .findFirst().orElseThrow();
    }

    private static <T extends Component> T find(Component root, Class<T> type, Predicate<T> match) {
        return descendants(root).filter(type::isInstance).map(type::cast).filter(match)
                .findFirst().orElseThrow(() -> new AssertionError("Missing " + type.getSimpleName()));
    }

    private static Stream<Component> descendants(Component component) {
        return component instanceof Container container
                ? Stream.concat(Stream.of(component),
                        Arrays.stream(container.getComponents()).flatMap(UnifiedWorkbenchPanelTest::descendants))
                : Stream.of(component);
    }
}
