package process.swing.workflow;

import process.ProcessWorkflowPipeline;
import process.PhaseExplanation.ModelReference;

import objectview.Viewable;
import objectview.render.RenderingMode;
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
        session.showPlan();
        session.dialog.setVisible(true);
        return session.dialog;
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
            install(action.process().plan().title(), panel);
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
            if (outcome == null || outcome.result() == null) {
                String message = outcome != null && outcome.error() != null
                        ? outcome.error().getMessage() : "No result was produced.";
                JOptionPane.showMessageDialog(owner, message);
                dialog.dispose();
                return;
            }
            state.results();
            ProcessWorkflowResults<D> results;
            try {
                results = action.results(outcome);
            } catch (Throwable resultFailure) {
                showFallbackResults(outcome.summary(), resultFailure, null);
                return;
            }
            try {
                showResults(results, outcome.status());
            } catch (Throwable renderingFailure) {
                showFallbackResults(outcome.summary(), renderingFailure, results);
            }
        }

        /** Never leave a completed process looking RUNNING because its rich result
         * page failed. Actions with one safe result (generation) can still be accepted. */
        void showFallbackResults(String summary, Throwable failure,
                                 ProcessWorkflowResults<D> results) {
            JPanel panel = page("3 · Results", summary);
            JLabel message = new JLabel("<html>The detailed result preview could not be "
                    + "rendered.<br>" + html(failure.getMessage()) + "</html>");
            panel.add(message, BorderLayout.CENTER);
            JButton close = new JButton("Close without applying");
            JButton accept = new JButton("Accept completed result");
            close.addActionListener(e -> dialog.dispose());
            List<ProcessWorkflowResults.Card<D>> safe = results == null ? List.of()
                    : results.tabs().stream().flatMap(tab -> tab.cards().stream())
                            .filter(ProcessWorkflowResults.Card::includeInApplyAll).toList();
            accept.setEnabled(!safe.isEmpty());
            accept.addActionListener(e -> apply(safe));
            panel.add(buttons(close, accept), BorderLayout.SOUTH);
            install(action.plan().title() + " — results", panel);
        }

        void showResults(ProcessWorkflowResults<D> results, ProcessStatus status) {
            applyVerb = results.applyVerb();
            JPanel panel = page("3 · Results", results.summary()
                    + (status == ProcessStatus.PARTIAL ? " (partial result)" : ""));
            AtomicReference<ProcessWorkflowResults.Card<D>> selected = new AtomicReference<>();
            JButton applySelected = new JButton(results.applyVerb() + " selected card");
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
                    tabs.setComponentAt(index, resultTab(tab, selected, applySelected));
                } catch (Throwable failure) {
                    tabs.setComponentAt(index, new JLabel("  Could not render this tab: "
                            + (failure.getMessage() == null
                            ? failure.getClass().getSimpleName() : failure.getMessage())));
                }
            };
            tabs.addChangeListener(e -> buildSelectedTab.run());
            buildSelectedTab.run(); // Summary is normally first and intentionally small.
            panel.add(tabs, BorderLayout.CENTER);
            JButton close = new JButton("Close without applying");
            JButton applyAll = new JButton(
                    results.applyVerb() + " all safe results (" + bulk.size() + ")");
            applyAll.setEnabled(!bulk.isEmpty());
            close.addActionListener(e -> dialog.dispose());
            applySelected.addActionListener(e -> apply(List.of(selected.get())));
            applyAll.addActionListener(e -> apply(bulk));
            panel.add(buttons(close, applySelected, applyAll), BorderLayout.SOUTH);
            install(results.title(), panel);
        }

        private JComponent resultTab(
                ProcessWorkflowResults.Tab<D> tab,
                AtomicReference<ProcessWorkflowResults.Card<D>> selected,
                JButton applySelected) {
            List<Viewable> views = tab.cards().stream()
                    .map(ProcessWorkflowResults.Card::view).toList();
            if (views.isEmpty()) return new JLabel("  (none)");
            java.util.Map<Viewable, ProcessWorkflowResults.Card<D>> cards =
                    new java.util.IdentityHashMap<>();
            tab.cards().forEach(card -> cards.put(card.view(), card));
            return SearchableView.builder(views).sample(views.get(0))
                    .mode(RenderingMode.CARD).collapsible(false).columns(2)
                    // RenderContext also asks about nested referenced Viewables.
                    // Only top-level result cards have workflow decorations.
                    .cardDecorator(view -> {
                        ProcessWorkflowResults.Card<D> card = cards.get(view);
                        return card == null ? null : card.decoration().get();
                    })
                    .selectionListener(value -> {
                        ProcessWorkflowResults.Card<D> card = value instanceof Viewable view
                                ? cards.get(view) : null;
                        selected.set(card);
                        applySelected.setEnabled(card != null
                                && card.decision().get() != null);
                    }).build();
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
