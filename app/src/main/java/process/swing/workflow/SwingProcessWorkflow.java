package process.swing.workflow;

import process.ProcessWorkflowPipeline;
import process.PhaseExplanation.ModelReference;

import objectview.Viewable;
import objectview.render.RenderingMode;
import objectview.utils.swing.SwingWindowActivation;
import objectview.view.SearchableView;
import process.ProcessOutcome;
import process.ProcessStatus;
import process.swing.SwingProcessRunner;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** One Plan → Running → Results → Apply Swing skeleton for every curation action. */
public final class SwingProcessWorkflow {
    private SwingProcessWorkflow() { }

    public static <R, D> JDialog start(
            Component owner, SwingProcessRunner runner, ProcessWorkflowAction<R, D> action) {
        return start(owner, runner, action, null);
    }

    public static <R, D> JDialog start(
            Component owner, SwingProcessRunner runner, ProcessWorkflowAction<R, D> action,
            Consumer<ModelReference> navigateReference) {
        Session<R, D> session = new Session<>(owner, runner, action, navigateReference);
        ProcessOutcome<R> prepared = action.preparedOutcome();
        if (prepared == null) session.showPlan();
        else session.review(prepared);
        return SwingWindowActivation.showAndFocus(session.dialog);
    }

    private static final class Session<R, D> {
        final Component owner;
        final SwingProcessRunner runner;
        final ProcessWorkflowAction<R, D> action;
        final ProcessWorkflowState state = new ProcessWorkflowState();
        // What this action's apply does, for the buttons and the confirmation — set
        // when the results arrive, since only the action knows.
        String applyVerb = "Apply";
        final JDialog dialog;
        final ProcessWorkflowPipeline pipeline;
        final ProcessWorkflowPipelinePanel pipelinePanel;
        boolean reviewingPrepared;

        Session(Component owner, SwingProcessRunner runner, ProcessWorkflowAction<R, D> action,
                Consumer<ModelReference> navigateReference) {
            this.owner = owner; this.runner = runner; this.action = action;
            this.pipeline = action.pipeline();
            this.pipelinePanel = pipeline == null ? null
                    : new ProcessWorkflowPipelinePanel(pipeline);
            if (pipelinePanel != null) {
                pipelinePanel.onNavigateReference(navigateReference);
            }
            dialog = new JDialog(SwingUtilities.getWindowAncestor(owner),
                    action.plan().title(), Dialog.ModalityType.MODELESS);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            dialog.setSize(new Dimension(940, 700));
            dialog.setLocationRelativeTo(owner);
        }

        void showPlan() {
            ProcessWorkflowPlan plan = action.plan();
            JPanel panel = page("1 · Plan", plan.description());
            JTabbedPane planTabs = tabs(plan.tabs());
            if (pipelinePanel != null) {
                planTabs.insertTab("Pipeline", null, pipelinePanel,
                        "Configured execution pipeline", 0);
                planTabs.setSelectedIndex(0);
            }
            JComponent executionSettings = action.executionSettings();
            if (executionSettings != null) {
                planTabs.insertTab("Execution settings", null, executionSettings,
                        "Run-scoped resource and reliability settings",
                        pipelinePanel == null ? 0 : 1);
            }
            panel.add(planTabs, BorderLayout.CENTER);
            JButton cancel = new JButton("Cancel");
            JButton execute = new JButton(plan.executable()
                    ? "Execute" : plan.noWorkMessage());
            execute.setEnabled(plan.executable());
            cancel.addActionListener(e -> dialog.dispose());
            execute.addActionListener(e -> execute());
            panel.add(buttons(cancel, execute), BorderLayout.SOUTH);
            install(plan.title(), panel);
        }

        void execute() {
            if (!action.plan().executable()) return;
            state.execute();
            if (pipeline != null) pipeline.reset();
            JPanel panel = page("2 · Running", action.process().plan().description());
            panel.add(pipelinePanel == null
                    ? new JLabel("Queries are running. Progress is recorded in Query logs.")
                    : pipelinePanel, BorderLayout.CENTER);
            JButton cancel = new JButton("Cancel process");
            cancel.addActionListener(e -> runner.cancel());
            panel.add(buttons(cancel), BorderLayout.SOUTH);
            // The window is named for what the reader started, not for whichever
            // component is currently talking. Expanding graph-frontier nodes runs a
            // generation, so the process called this "Generate domain" and the reason
            // the reader gave it — the two nodes they chose — disappeared exactly when
            // the work they were watching began. The phase is already on the page.
            install(action.plan().title(), panel);
            // SwingProcessRunner.done() already invokes both callbacks on the EDT.
            // Re-queueing completion detached exceptions from the runner's guard and
            // could strand a completed result behind the stale Running page forever.
            runner.run(action.process(), this::completed, error -> {
                        if (pipeline != null) pipeline.finish(ProcessStatus.FAILED,
                                error == null ? "Process failed" : error.getMessage());
                        JOptionPane.showMessageDialog(owner, "Process failed: " + error.getMessage());
                        dialog.dispose();
                    });
        }

        void completed(ProcessOutcome<R> outcome) {
            if (pipeline != null && outcome != null) {
                pipeline.finish(outcome.status(), outcome.summary());
            }
            state.results();
            presentResults(outcome);
        }

        /** Review state produced by an earlier operation, without a pretend Execute. */
        void review(ProcessOutcome<R> outcome) {
            reviewingPrepared = true;
            state.review();
            presentResults(outcome);
        }

        private void presentResults(ProcessOutcome<R> outcome) {
            if (outcome == null || outcome.result() == null) {
                String message = outcome != null && outcome.error() != null
                        ? outcome.error().getMessage() : "No result was produced.";
                JOptionPane.showMessageDialog(owner, message);
                dialog.dispose();
                return;
            }
            ProcessWorkflowResults<D> results;
            try {
                results = action.results(outcome);
            } catch (Throwable resultFailure) {
                showFallbackResults(outcome.summary(), resultFailure, null, outcome.status());
                return;
            }
            try {
                showResults(results, outcome.status());
            } catch (Throwable renderingFailure) {
                showFallbackResults(outcome.summary(), renderingFailure, results,
                        outcome.status());
            }
        }

        /** Never leave a completed process looking RUNNING because its rich result
         * page failed. Actions with one safe result (generation) can still be accepted. */
        void showFallbackResults(String summary, Throwable failure,
                                 ProcessWorkflowResults<D> results, ProcessStatus status) {
            JPanel panel = page(reviewingPrepared ? "Review" : "3 · Results", summary);
            JLabel message = new JLabel("<html>The detailed result preview could not be "
                    + "rendered.<br>" + html(failure.getMessage()) + "</html>");
            panel.add(message, BorderLayout.CENTER);
            JButton close = new JButton("Close without applying");
            JButton accept = new JButton("Accept completed result");
            close.addActionListener(e -> dialog.dispose());
            List<ProcessWorkflowResults.Card<D>> safe = results == null ? List.of()
                    : results.tabs().stream().flatMap(tab -> tab.cards().stream())
                            .filter(ProcessWorkflowResults.Card::includeInApplyAll).toList();
            accept.setEnabled(action.applyAllowed(status) && !safe.isEmpty());
            accept.addActionListener(e -> apply(safe));
            panel.add(buttons(close, accept), BorderLayout.SOUTH);
            install(action.plan().title() + " — results", panel);
        }

        void showResults(ProcessWorkflowResults<D> results, ProcessStatus status) {
            applyVerb = results.applyVerb();
            boolean applicationAllowed = action.applyAllowed(status);
            JPanel panel = page(reviewingPrepared ? "Review" : "3 · Results", results.summary()
                    + (status == ProcessStatus.PARTIAL
                    ? applicationAllowed ? " (partial result)"
                    : " (partial result — applying is disabled by execution settings)"
                    : ""));
            AtomicReference<List<ProcessWorkflowResults.Card<D>>> selected =
                    new AtomicReference<>(List.of());
            JButton applySelected = new JButton(results.applyVerb() + " selected card"
                    + (action.multipleResultSelection() ? "s" : ""));
            applySelected.setEnabled(false);
            List<ProcessWorkflowResults.Card<D>> bulk = results.tabs().stream()
                    .flatMap(tab -> tab.cards().stream())
                    .filter(ProcessWorkflowResults.Card::includeInApplyAll).toList();
            JTabbedPane tabs = new JTabbedPane();
            int fixedTabCount = 0;
            if (pipelinePanel != null) {
                tabs.addTab("Pipeline", pipelinePanel);
                fixedTabCount = 1;
            }
            for (ProcessWorkflowResults.Tab<D> tab : results.tabs()) {
                // Large result tabs (20k+ cards are normal) stay lazy. Building and
                // search-indexing every tab here blocks the EDT after the process has
                // completed, leaving the stale Running page and Cancel button visible.
                JPanel placeholder = new JPanel(new BorderLayout());
                placeholder.add(new JLabel("  Open this tab to render its results"),
                        BorderLayout.NORTH);
                tabs.addTab(tab.title() + " (" + tab.cards().size() + ")", placeholder);
            }
            java.util.Set<Integer> builtTabs = new java.util.HashSet<>();
            final int resultTabOffset = fixedTabCount;
            Runnable buildSelectedTab = () -> {
                int index = tabs.getSelectedIndex();
                if (index < resultTabOffset) return;
                int resultIndex = index - resultTabOffset;
                if (resultIndex < 0 || resultIndex >= results.tabs().size()
                        || !builtTabs.add(resultIndex)) return;
                ProcessWorkflowResults.Tab<D> tab = results.tabs().get(resultIndex);
                try {
                    tabs.setComponentAt(index, resultTab(
                            tab, selected, applySelected, applicationAllowed));
                } catch (Throwable failure) {
                    tabs.setComponentAt(index, new JLabel("  Could not render this tab: "
                            + (failure.getMessage() == null
                            ? failure.getClass().getSimpleName() : failure.getMessage())));
                }
            };
            Runnable syncApplyToTab = () -> {
                int index = tabs.getSelectedIndex() - resultTabOffset;
                boolean actionable = index >= 0 && index < results.tabs().size()
                        && actionable(results.tabs().get(index));
                selected.set(List.of());
                applySelected.setEnabled(false);
                // Hidden, not greyed. A disabled Expand beside expanded nodes reads as
                // "you may not do this yet"; there is nothing to do here at all.
                applySelected.setVisible(actionable);
            };
            tabs.addChangeListener(e -> {
                buildSelectedTab.run();
                syncApplyToTab.run();
            });
            buildSelectedTab.run(); // Summary is normally first and intentionally small.
            syncApplyToTab.run();
            panel.add(tabs, BorderLayout.CENTER);
            JButton close = new JButton("Close without applying");
            close.addActionListener(e -> dialog.dispose());
            applySelected.addActionListener(e -> apply(selected.get()));
            // Offered only when some result opted into it. A workflow whose every
            // decision is deliberate marks none — expanding the whole graph frontier
            // is the growth a curated frontier exists to prevent — and a permanently
            // dead button reading "all safe results (0)" reads as "no results" rather
            // than "this action is not on offer here".
            if (bulk.isEmpty()) {
                panel.add(buttons(close, applySelected), BorderLayout.SOUTH);
            } else {
                JButton applyAll = new JButton(
                        results.applyVerb() + " all safe results (" + bulk.size() + ")");
                applyAll.setEnabled(applicationAllowed);
                applyAll.addActionListener(e -> apply(bulk));
                panel.add(buttons(close, applySelected, applyAll), BorderLayout.SOUTH);
            }
            install(action.plan().title(), panel);
        }

        /** Whether applying from this tab could do anything: some card decides. */
        private boolean actionable(ProcessWorkflowResults.Tab<D> tab) {
            return tab.cards().stream().anyMatch(card -> card.decision().get() != null);
        }

        private JComponent resultTab(
                ProcessWorkflowResults.Tab<D> tab,
                AtomicReference<List<ProcessWorkflowResults.Card<D>>> selected,
                JButton applySelected,
                boolean applicationAllowed) {
            List<Viewable> views = tab.cards().stream()
                    .map(ProcessWorkflowResults.Card::view).toList();
            if (views.isEmpty()) return new JLabel("  (none)");
            java.util.Map<Viewable, ProcessWorkflowResults.Card<D>> cards =
                    new java.util.IdentityHashMap<>();
            tab.cards().forEach(card -> cards.put(card.view(), card));
            SearchableView.Builder builder = SearchableView.builder(views).sample(views.get(0))
                    .mode(RenderingMode.CARD).collapsible(false).columns(2)
                    // RenderContext also asks about nested referenced Viewables.
                    // Only top-level result cards have workflow decorations.
                    .cardDecorator(view -> {
                        ProcessWorkflowResults.Card<D> card = cards.get(view);
                        return card == null ? null : card.decoration().get();
                    });
            if (action.multipleResultSelection()) {
                builder.selectionSetListener(values -> {
                        List<ProcessWorkflowResults.Card<D>> chosen = values.stream()
                                .filter(Viewable.class::isInstance)
                                .map(Viewable.class::cast).map(cards::get)
                                .filter(java.util.Objects::nonNull)
                                .filter(card -> card.decision().get() != null).toList();
                        selected.set(chosen);
                        applySelected.setText(resultsApplyLabel(
                                applyVerb, chosen.size()));
                        applySelected.setEnabled(applicationAllowed && !chosen.isEmpty());
                    });
            } else {
                builder.selectionListener(value -> {
                    ProcessWorkflowResults.Card<D> card = value instanceof Viewable view
                            ? cards.get(view) : null;
                    boolean actionable = card != null && card.decision().get() != null;
                    selected.set(actionable ? List.of(card) : List.of());
                    applySelected.setEnabled(applicationAllowed && actionable);
                });
            }
            SearchableView view = builder.build();
            // A tab whose cards carry no decision cannot be applied from — the listeners
            // above already filter those out, so selecting there moves nothing. Showing
            // Select all beside cards that answer nothing is an offer the workflow
            // cannot keep.
            if (!action.multipleResultSelection() || !actionable(tab)) return view;
            JPanel panel = new JPanel(new BorderLayout(4, 4));
            JPanel selection = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            JButton selectAll = new JButton("Select all");
            JButton clear = new JButton("Clear selection");
            selectAll.addActionListener(e -> view.renderContext().selectAll());
            clear.addActionListener(e -> view.renderContext().clearSelection());
            selection.add(selectAll);
            selection.add(clear);
            selection.add(new JLabel("Shift-click selects an interval"));
            panel.add(selection, BorderLayout.NORTH);
            panel.add(view, BorderLayout.CENTER);
            return panel;
        }

        private static String resultsApplyLabel(String verb, int selected) {
            return verb + " selected card" + (selected == 1 ? "" : "s")
                    + (selected == 0 ? "" : " (" + selected + ")");
        }

        void apply(List<ProcessWorkflowResults.Card<D>> cards) {
            List<D> decisions = cards.stream().map(card -> card == null ? null : card.decision().get())
                    .filter(java.util.Objects::nonNull).toList();
            if (decisions.isEmpty()) return;
            state.apply();
            try {
                action.apply(decisions);
                state.applied();
                JOptionPane.showMessageDialog(owner,
                        applyVerb + ": " + decisions.size() + " decision(s).");
                dialog.dispose();
            } catch (Exception error) {
                state.retryApply();
                JOptionPane.showMessageDialog(owner, "Apply failed: " + error.getMessage());
                return;
            }
            // A continuation that opens another workflow must run after this window
            // is gone; it is not part of the already-committed apply transaction.
            try {
                action.afterApply();
            } catch (RuntimeException continuationFailure) {
                JOptionPane.showMessageDialog(owner,
                        "Applied, but the next window could not be opened: "
                                + continuationFailure.getMessage());
            }
        }

        private JTabbedPane tabs(List<ProcessWorkflowPlan.Tab> definitions) {
            JTabbedPane tabs = new JTabbedPane();
            for (ProcessWorkflowPlan.Tab tab : definitions) {
                List<Viewable> cards = new ArrayList<>(tab.cards());
                JComponent content = cards.isEmpty() ? new JLabel("  (none)")
                        : SearchableView.builder(cards).sample(cards.get(0))
                                .mode(RenderingMode.CARD).collapsible(false).columns(2)
                                .cardDecorator(tab.decoration()).build();
                tabs.addTab(tab.title() + " (" + cards.size() + ")", content);
            }
            return tabs;
        }

        private JPanel page(String stage, String description) {
            JPanel panel = new JPanel(new BorderLayout(8, 8));
            panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));
            panel.add(new JLabel("<html><b>" + stage + "</b><br>"
                    + html(description) + "</html>"), BorderLayout.NORTH);
            return panel;
        }

        private void install(String title, JPanel panel) {
            dialog.setTitle(title);
            dialog.setContentPane(panel);
            dialog.revalidate(); dialog.repaint();
        }

        private static JPanel buttons(JButton... values) {
            JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
            for (JButton value : values) panel.add(value);
            return panel;
        }

        private static String html(String value) {
            return value == null ? "" : value.replace("&", "&amp;")
                    .replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
