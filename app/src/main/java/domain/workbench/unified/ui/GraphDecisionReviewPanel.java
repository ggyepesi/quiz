package domain.workbench.unified.ui;

import domain.build.GraphDiscoveryResultStore;
import domain.build.GraphResults;
import domain.config.GeneratedClassModel;
import domain.transform.ui.ProjectBacking;
import domain.workbench.execution.ui.QueryObjectResultPanel;
import domain.workbench.execution.ui.GraphResultPresentation;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Reviews one saved graph result in the open project's action pane. */
final class GraphDecisionReviewPanel extends JPanel {
    private final QueryObjectResultPanel cards = new QueryObjectResultPanel();
    private final JButton load = new JButton("Load graph annotations");
    private final JLabel status = new JLabel();
    private final Set<GeneratedClassModel> edited = new LinkedHashSet<>();
    private final Consumer<Boolean> onLoading;
    private final Consumer<String> report;
    private final Runnable annotationsChanged;
    private final BooleanSupplier beforeLoad;
    private ProjectBacking project;
    private GraphResults results;
    private GeneratedClassModel selected;
    private boolean loading;
    private boolean enabled = true;

    GraphDecisionReviewPanel(ProjectBacking project, BooleanSupplier beforeLoad,
            Consumer<Boolean> onLoading, Consumer<String> report, Runnable annotationsChanged) {
        super(new BorderLayout(0, 4));
        this.beforeLoad = beforeLoad;
        this.onLoading = onLoading;
        this.report = report;
        this.annotationsChanged = annotationsChanged;
        var tools = new JPanel(new FlowLayout(FlowLayout.LEFT));
        tools.add(load);
        tools.add(status);
        add(tools, BorderLayout.NORTH);
        load.addActionListener(event -> loadAnnotations());
        reset(project);
    }

    void reset(ProjectBacking project) {
        reset(project, project.saveGuard() == null ? null
                : new GraphResults(project.projectModel(), project.saveGuard().storage()));
    }

    void reset(ProjectBacking project, GraphResults restored) {
        this.project = project;
        results = restored;
        edited.clear();
        select(null);
    }

    void useProject(ProjectBacking project) { this.project = project; }

    GraphResults results() { return results; }

    List<GraphDiscoveryResultStore.Artifact> annotations() {
        return results == null ? List.of() : results.all();
    }

    void select(GeneratedClassModel graph) {
        selected = graph != null && !graph.isImported() && graph.graphSource() != null ? graph : null;
        refresh();
    }

    void setReviewEnabled(boolean enabled) {
        this.enabled = enabled;
        cards.setResultActionsEnabled(enabled);
        load.setEnabled(enabled && !loading && results != null && selected != null);
    }

    boolean isLoading() { return loading; }

    private void refresh() {
        load.setVisible(selected != null && results != null);
        setReviewEnabled(enabled);
        remove(cards);
        if (loading) return;
        cards.clear();
        if (selected == null) {
            status.setText("Select a graph class to review its decisions.");
        } else if (results == null) {
            status.setText("Open a saved project to review graph decisions.");
        } else {
            var artifact = results.of(selected);
            if (artifact == null) {
                status.setText("Load " + results.destination(selected).getPath());
            } else {
                showResult(selected, artifact);
            }
        }
    }

    File annotationFile(GeneratedClassModel graph) {
        return results.destination(graph);
    }

    void loadAnnotations() {
        if (!enabled || loading || selected == null || results == null
                || results.of(selected) != null || !beforeLoad.getAsBoolean()) return;
        var graph = selected;
        var guard = project.saveGuard();
        var graphResults = results;
        String path = graphResults.destination(graph).getPath();
        loading = true;
        status.setText("Loading " + path + "…");
        onLoading.accept(true);
        new SwingWorker<GraphDiscoveryResultStore.Artifact, Void>() {
            @Override protected GraphDiscoveryResultStore.Artifact doInBackground() throws Exception {
                guard.check();
                var result = graphResults.restore(graph, report);
                guard.check();
                return result;
            }

            @Override protected void done() {
                loading = false;
                try {
                    var artifact = get();
                    if (graph != selected || graphResults != results) {
                        refresh();
                    } else if (artifact == null) {
                        status.setText("No graph annotations in " + path + ". Use Run graph first.");
                    } else {
                        showResult(graph, artifact);
                    }
                    if (artifact != null && graphResults == results) annotationsChanged.run();
                } catch (Exception failure) {
                    graphResults.invalidate(graph);
                    var cause = failure.getCause() == null ? failure : failure.getCause();
                    status.setText("Could not load " + path + ". See Query logs.");
                    report.accept("Could not load " + path + ": " + cause.getMessage());
                } finally {
                    onLoading.accept(false);
                }
            }
        }.execute();
    }

    private void showResult(GeneratedClassModel graph, GraphDiscoveryResultStore.Artifact artifact) {
        load.setVisible(false);
        if (!artifact.configurationSignature().isBlank()
                && !artifact.configurationSignature().equals(
                        GraphResults.configurationSignature(project.projectModel(), graph))) {
            status.setText("The configuration changed. Run " + graph.className() + " again before review.");
            return;
        }
        updateStatus(artifact);
        add(cards, BorderLayout.CENTER);
        revalidate();
        cards.acceptGrouped(null, Map.of(artifact.type(), GraphResultPresentation.review(
                artifact, List.of(), () -> {
                    markEdited(graph, "Graph decisions for " + graph.className());
                    updateStatus(artifact);
                })));
    }

    GraphDiscoveryResultStore.Artifact previousForRun(GeneratedClassModel graph) throws Exception {
        var guard = project.saveGuard();
        guard.check();
        var previous = results.of(graph);
        if (previous == null) {
            var file = results.destination(graph);
            if (file.isFile()) report.accept("Reading previous graph decisions from " + file.getPath() + ".");
            previous = results.readSaved(graph);
        }
        guard.check();
        return previous;
    }

    void keepRun(GeneratedClassModel graph, GraphDiscoveryResultStore.Artifact artifact) {
        if (!artifact.instances().isEmpty() && !artifact.configurationSignature().equals(
                GraphResults.configurationSignature(project.projectModel(), graph))) {
            throw new IllegalStateException("The graph configuration has changed. Run it again.");
        }
        results.keep(graph, artifact);
        markEdited(graph, "Graph annotations for " + graph.className());
        if (selected == graph) refresh();
    }

    private void markEdited(GeneratedClassModel graph, String change) {
        edited.add(graph);
        project.recordModelChange(change);
        annotationsChanged.run();
    }

    private void updateStatus(GraphDiscoveryResultStore.Artifact artifact) {
        int awaiting = artifact.awaitingDecision().size();
        status.setText(artifact.type() + " — " + awaiting + " Review "
                + (awaiting == 1 ? "entry needs" : "entries need") + " a manual decision."
                + (edited.contains(selected) ? " Save your decisions before building." : ""));
    }

    List<File> editedFiles() {
        return edited.isEmpty() ? List.of() : edited.stream().map(results::destination).toList();
    }

    /** Called inside the same guarded save that writes the working model. */
    void writeEdits() throws Exception {
        for (var graph : edited) {
            GraphDiscoveryResultStore.save(results.of(graph), project.saveGuard().storage());
        }
    }

    void saved() {
        edited.clear();
        var artifact = results == null ? null : results.of(selected);
        if (artifact != null) updateStatus(artifact);
    }
}
