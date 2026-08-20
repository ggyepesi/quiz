package wikidata.explore.query.swing;

import objectview.render.CardListView;
import objectview.render.RenderContext;
import objectview.search.SearchPanel;
import objectview.viewconfig.ViewConfig;
import wikidata.explore.query.core.QueryStatus;
import wikidata.explore.query.log.LogKind;
import wikidata.explore.query.log.LogListener;
import wikidata.explore.query.log.LogNode;
import wikidata.explore.query.log.LogText;
import wikidata.explore.query.log.WorkflowRecorder;
import wikidata.explore.query.log.SavedRunArtifact;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class WorkflowLogWindow implements LogListener {

    private final List<LogNode> workflows =
            new ArrayList<>();
    private final List<RegisteredPipeline> pipelines = new ArrayList<>();

    private CardListView view;
    private JFrame frame;

    // Set when an update was skipped because the user had scrolled up (so their
    // selection wasn't destroyed); replayed when they return to the bottom.
    private boolean deferredUpdates;

    @Override
    public void logChanged(
            LogNode root,
            boolean added) {

        SwingUtilities.invokeLater(() -> {
            if (added && !workflows.contains(root)) {
                workflows.add(root);
            }

            if (view != null) {
                // Tail behaviour: follow the newest entry during generation, but
                // only when already at the bottom — so scrolling up to read isn't
                // yanked back.
                JScrollBar bar = verticalBar();
                boolean atBottom = bar == null
                        || bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 48;

                // Only refresh the cards while tailing (at the bottom). Once the user
                // has scrolled up to READ or SELECT, the continuous upserts during a
                // run were rebuilding the cards under the cursor and destroying the
                // selection — so hold updates until they scroll back down (the LogNode
                // data still updates; only the redraw is deferred).
                if (atBottom) {
                    view.upsertViewable(root);
                    if (bar != null) {
                        // After the upsert lays out, jump to the (new) bottom.
                        SwingUtilities.invokeLater(() -> bar.setValue(bar.getMaximum()));
                    }
                } else {
                    // Keep cheap mutable titles such as "steps (N)" live while the
                    // full card rebuild is deferred to protect selection/scroll.
                    view.refreshInlineCollectionCounts(root);
                    deferredUpdates = true;
                }
            }
        });
    }

    private JScrollBar verticalBar() {
        if (view == null) {
            return null;
        }
        Component sp = view.getCardsScrollPane();
        return sp instanceof JScrollPane jsp ? jsp.getVerticalScrollBar() : null;
    }

    public void show(Component owner) {
        if (frame != null) {
            if ((frame.getExtendedState() & Frame.ICONIFIED) != 0) {
                frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
            }
            frame.setVisible(true);
            frame.toFront();
            frame.requestFocus();
            return;
        }

        CardListView v =
                new CardListView();

        // Birdseye: each query-log entry renders collapsed (title + toggle) and
        // expands on demand, so a long history (and long entries like the name-
        // collision list) stays scannable. Context must be set before the cards
        // build so they pick it up; shared, so streamed new entries collapse too.
        RenderContext ctx = new RenderContext();
        ctx.setCollapsibleCards(true);
        v.setRenderContext(ctx);

        for (LogNode workflow : workflows) {
            v.addViewable(workflow);
        }

        v.createCardsPanel(1);

        // Search every field of the entry, not just its title: what a reader looks for
        // in a log — a QID, a PID, a property in a request — is on the steps BELOW the
        // card, and the default search config is the display name alone. The card
        // itself is the whole tree, so its search config is the whole entry too.
        SearchPanel search =
                new SearchPanel(LogNode.class, null,
                        new SearchPanel.ConfigState(
                                ViewConfig.of(LogNode.class), null, null));

        search.setTarget(
                v.getCardsPanel(),
                v.getCardsScrollPane());

        v.addTargetListener(search);

        JFrame f =
                new JFrame("Query Logs");

        f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        f.setLayout(new BorderLayout(6, 6));

        // Save the whole log, fully expanded, to a text file — for diffing two runs
        // offline regardless of what's collapsed in the UI.
        JButton saveButton = new JButton("Save log…");
        saveButton.setToolTipText("Save the entire log (fully expanded) to a text file");
        saveButton.addActionListener(e -> saveLog(f));
        JPanel north = new JPanel(new BorderLayout(6, 6));
        north.add(search, BorderLayout.CENTER);
        north.add(saveButton, BorderLayout.EAST);
        f.add(north, BorderLayout.NORTH);
        f.add(v.getCardsScrollPane(), BorderLayout.CENTER);
        f.setSize(1000, 700);
        f.setLocationRelativeTo(owner);

        frame = f;
        view = v;

        // Catch up on updates deferred while scrolled up, once the user returns to
        // the bottom (e.g. a run that finished while they were reading/selecting).
        JScrollBar catchUpBar = verticalBar();
        if (catchUpBar != null) {
            catchUpBar.addAdjustmentListener(e -> {
                boolean atBottom = catchUpBar.getValue() + catchUpBar.getVisibleAmount()
                        >= catchUpBar.getMaximum() - 48;
                if (atBottom && deferredUpdates && view != null) {
                    deferredUpdates = false;
                    for (LogNode w : new ArrayList<>(workflows)) {
                        view.upsertViewable(w);
                    }
                }
            });
        }

        f.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                frame = null;
                view = null;
            }
        });

        f.setVisible(true);
    }

    /** Associates a live executable pipeline with the log history saved afterwards. */
    public synchronized void registerPipeline(
            String title, process.ProcessWorkflowPipeline pipeline) {
        if (pipeline == null) return;
        pipelines.add(new RegisteredPipeline(
                title == null || title.isBlank() ? "Pipeline" : title, pipeline));
    }

    private void saveLog(Component parent) {
        if (workflows.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "The log is empty — nothing to save.");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save query log");
        chooser.setSelectedFile(new java.io.File("query-log.txt"));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        java.io.File file = chooser.getSelectedFile();
        try {
            String text = LogText.toText(workflows);
            java.nio.file.Files.writeString(file.toPath(), text);
            List<SavedRunArtifact.PipelineRun> snapshots;
            synchronized (this) {
                snapshots = pipelines.stream().map(p ->
                        new SavedRunArtifact.PipelineRun(
                                p.title(), p.pipeline().snapshot())).toList();
            }
            SavedRunArtifact.capture(text, snapshots)
                    .write(SavedRunArtifact.companionPath(file.toPath()));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent,
                    "Could not save the log: " + ex.getMessage(),
                    "Save failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private record RegisteredPipeline(
            String title, process.ProcessWorkflowPipeline pipeline) {}

    public void info(String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        WorkflowRecorder recorder =
                new WorkflowRecorder(
                        new LogNode(LogKind.MESSAGE, "Log message"));

        recorder.setListener(this);
        recorder.added();
        recorder.start();
        recorder.message(text);
        recorder.finish(QueryStatus.OK, null, null);
    }

    /** One collapsible row of a {@link #structuredEntry}. */
    public record Row(String title, String summary, String detail) {}

    /**
     * Adds a single structured entry: a titled root with a short {@code summary}
     * and one collapsible child {@link Row} per item — instead of one giant text
     * blob. Keeps long lists (e.g. every colliding QID) off the top level so a
     * specific entry stays reachable.
     */
    public void structuredEntry(String title, String summary, List<Row> rows) {
        WorkflowRecorder recorder =
                new WorkflowRecorder(
                        new LogNode(LogKind.MESSAGE, title == null ? "" : title));

        recorder.setListener(this);
        recorder.added();
        recorder.start();
        LogNode root = recorder.root();
        if (rows != null) {
            for (Row r : rows) {
                if (r == null) {
                    continue;
                }
                recorder.addSubquery(
                        root, r.title(), "",
                        r.detail() == null ? "" : r.detail(),
                        r.summary());
            }
        }
        recorder.finish(QueryStatus.OK, summary, null);
    }
}
