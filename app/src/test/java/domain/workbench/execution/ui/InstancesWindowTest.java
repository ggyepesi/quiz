package domain.workbench.execution.ui;

import dataset.DomainStorage;
import domain.build.GenerationRun;
import domain.build.GraphDiscoveryResultStore;
import domain.build.GraphResults;
import domain.compile.codegen.GeneratedViewableRuntime;
import domain.config.ClassKind;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.runtime.SnapshotDomain;
import domain.runtime.WikidataDynamicObject;
import domain.transform.DynamicViewable;
import domain.workbench.execution.GenerationRunState;
import objectview.Viewable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstancesWindowTest {
    @TempDir Path directory;

    @Test void aRunRefreshesTheAlreadyOpenWindowAndKeepsTheExistingToolbar() throws Exception {
        var model = model();
        var state = new GenerationRunState();
        var storage = DomainStorage.in(directory.toFile());
        var graphs = new GraphResults(model, storage);
        var window = instances(model, state, storage, graphs, ignored -> { });

        try {
            SwingUtilities.invokeAndWait(window::show);
            JFrame frame = window.window();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("Generated instances", frame.getTitle());
                assertEquals(List.of("Name collisions", "Graph frontier",
                        "Create population selection…"), toolbar(frame).stream().map(JButton::getText).toList());
                assertFalse(button(frame, "Name collisions").isEnabled());
                assertFalse(button(frame, "Create population selection…").isEnabled());

                state.accept(run(model, List.of(position("Q1"), position("Q2"))), true);
                window.refreshRun();
                state.refreshNameCollisions();
                window.nameCollisionsChanged();
            });
            SwingUtilities.invokeAndWait(() -> {
                assertSame(frame, window.window());
                assertEquals("Generated instances — Position 2  (2 distinct)", frame.getTitle());
                assertTrue(button(frame, "Create population selection…").isEnabled());
                assertTrue(button(frame, "Name collisions (1)").isEnabled());
                assertFalse(button(frame, "Graph frontier").isEnabled());
                button(frame, "Name collisions (1)").doClick(0);
                var collision = Arrays.stream(Window.getWindows())
                        .filter(w -> w instanceof JFrame f && "Name collisions".equals(f.getTitle()))
                        .filter(Window::isVisible).findFirst().orElseThrow();
                frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
                assertFalse(frame.isVisible(), "closing hides the instances frame");
                window.show();
                assertSame(frame, window.window(), "Show instances reuses the same frame");
                window.close();
                assertFalse(frame.isDisplayable());
                assertFalse(collision.isDisplayable(), "a project switch closes collision inspection too");
            });
        } finally {
            SwingUtilities.invokeAndWait(window::close);
        }
    }

    @Test void eachGraphKeepsItsOriginalDecisionTabsAndDelegatesApplyToTheHost() throws Exception {
        var model = model();
        var graph = new GeneratedClassModel("PositionGraph");
        graph.classKind(ClassKind.GRAPH);
        model.addClass(graph);
        var first = position("Q1");
        var second = position("Q2");
        var third = position("Q3");
        var accepted = annotation(first, "Accepted");
        var review = annotation(second, "Review");
        var rejected = annotation(third, "Rejected");
        var annotations = List.of(accepted, review, rejected);
        var state = new GenerationRunState();
        state.accept(run(model, List.of(first, second, third)), true);
        var storage = DomainStorage.in(directory.toFile());
        var graphs = new GraphResults(model, storage);
        graphs.keep(graph, new GraphDiscoveryResultStore.Artifact(model.name(), graph.className(),
                "Position", annotations, List.of(first, second, third),
                new SnapshotDomain(annotations, GraphDiscoveryResultStore.fieldGraph(
                        graph.className(), "Position", annotations))));
        var applied = new AtomicReference<GraphDiscoveryResultStore.Artifact>();
        var window = instances(model, state, storage, graphs, applied::set);

        try {
            SwingUtilities.invokeAndWait(window::show);
            SwingUtilities.invokeAndWait(() -> {
                var frame = window.window();
                assertEquals("Generated instances — Position 3  (3 distinct)"
                        + " · graph annotations: PositionGraph 3", frame.getTitle());
                var tabs = descendants(frame, JTabbedPane.class)
                        .filter(t -> titles(t).equals(List.of("Position", "PositionGraph")))
                        .findFirst().orElseThrow();
                tabs.setSelectedIndex(1);
                var partitions = descendants(tabs.getSelectedComponent(), JTabbedPane.class)
                        .filter(t -> t.getTabCount() > 0 && t.getTitleAt(0).startsWith("All ("))
                        .findFirst().orElseThrow();
                assertEquals(List.of("All (3)", "Accepted (1)", "Review (1)", "Rejected (1)"),
                        titles(partitions));
                partitions.setSelectedIndex(2);
                var viewer = descendants(frame, QueryObjectResultPanel.class).findFirst().orElseThrow();
                viewer.activeRenderContext().select(second);
                button(frame, "Reject selection").doClick(0);
                assertEquals("Rejected", review.get(GraphDiscoveryResultStore.MANUAL_DECISION));
                assertNull(accepted.get(GraphDiscoveryResultStore.MANUAL_DECISION));
                assertEquals("Review (1)", partitions.getTitleAt(partitions.getSelectedIndex()));
                assertTrue(state.hasUnsavedInstances());
                assertNull(applied.get(), "recording a manual decision does not apply the graph");
                button(frame, "Apply accepted instances").doClick(0);
                assertSame(graphs.of(graph), applied.get());
            });
        } finally {
            SwingUtilities.invokeAndWait(window::close);
        }
    }

    private static InstancesWindow instances(GeneratedProjectModel model, GenerationRunState state,
            DomainStorage storage, GraphResults graphs,
            java.util.function.Consumer<GraphDiscoveryResultStore.Artifact> apply) {
        return new InstancesWindow(null, model, state, graphs, () -> { }, apply,
                () -> storage.modelFile(model.name()), ignored -> { });
    }

    private static GeneratedProjectModel model() {
        var model = new GeneratedProjectModel();
        model.name("Positions");
        model.rootClass().className("Position");
        model.rootClass().classKind(ClassKind.SOURCE);
        return model;
    }

    private static GenerationRun run(GeneratedProjectModel model, List<WikidataDynamicObject> instances) {
        var runtime = new GeneratedViewableRuntime(model.rootClass(), "Position", "", null, null);
        // The downloaded carriers and the materialized instances are separate.
        List<Viewable> mapped = instances.stream().<Viewable>map(value -> {
            var instance = new DynamicViewable(value.getIdentifier(), value.getDisplayName());
            instance.type(value.typeName());
            return instance;
        }).toList();
        return new GenerationRun(model, 0, null, instances, runtime, mapped);
    }

    private static WikidataDynamicObject position(String id) {
        var position = new WikidataDynamicObject(id, "Shared name");
        position.type("Position");
        return position;
    }

    private static WikidataDynamicObject annotation(WikidataDynamicObject candidate, String decision) {
        var annotation = new WikidataDynamicObject(candidate.qid(), candidate.getDisplayName());
        annotation.type("PositionGraph");
        annotation.put(GraphDiscoveryResultStore.ANNOTATED_INSTANCE, candidate);
        annotation.put(GraphDiscoveryResultStore.GRAPH_DECISION, decision);
        return annotation;
    }

    private static List<JButton> toolbar(JFrame frame) {
        JPanel content = (JPanel) frame.getContentPane();
        Component toolbar = ((BorderLayout) content.getLayout())
                .getLayoutComponent(BorderLayout.NORTH);
        return descendants(toolbar, JButton.class).toList();
    }

    private static JButton button(Component root, String label) {
        return descendants(root, JButton.class)
                .filter(button -> label.equals(button.getText()) && button.isShowing())
                .findFirst().orElseThrow(() -> new AssertionError("No visible " + label + " button"));
    }

    private static List<String> titles(JTabbedPane tabs) {
        return IntStream.range(0, tabs.getTabCount()).mapToObj(tabs::getTitleAt).toList();
    }

    private static <T> Stream<T> descendants(Component root, Class<T> type) {
        return Stream.concat(type.isInstance(root) ? Stream.of(type.cast(root)) : Stream.empty(),
                root instanceof Container container ? Arrays.stream(container.getComponents())
                        .flatMap(child -> descendants(child, type)) : Stream.empty());
    }
}
