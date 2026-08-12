package process.swing.workflow;

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

/** One Plan → Running → Results → Apply Swing skeleton for every curation action. */
public final class SwingProcessWorkflow {
    private SwingProcessWorkflow() { }

    public static <R, D> JDialog start(
            Component owner, SwingProcessRunner runner, ProcessWorkflowAction<R, D> action) {
        Session<R, D> session = new Session<>(owner, runner, action);
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

        Session(Component owner, SwingProcessRunner runner, ProcessWorkflowAction<R, D> action) {
            this.owner = owner; this.runner = runner; this.action = action;
            dialog = new JDialog(SwingUtilities.getWindowAncestor(owner),
                    action.plan().title(), Dialog.ModalityType.MODELESS);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            dialog.setSize(new Dimension(940, 700));
            dialog.setLocationRelativeTo(owner);
        }

        void showPlan() {
            ProcessWorkflowPlan plan = action.plan();
            JPanel panel = page("1 · Plan", plan.description());
            panel.add(tabs(plan.tabs()), BorderLayout.CENTER);
            JButton cancel = new JButton("Cancel");
            JButton execute = new JButton("Execute");
            cancel.addActionListener(e -> dialog.dispose());
            execute.addActionListener(e -> execute());
            panel.add(buttons(cancel, execute), BorderLayout.SOUTH);
            install(plan.title(), panel);
        }

        void execute() {
            state.execute();
            JPanel panel = page("2 · Running", action.process().plan().description());
            JLabel status = new JLabel("Queries are running. Progress is recorded in Query logs.");
            panel.add(status, BorderLayout.CENTER);
            JButton cancel = new JButton("Cancel process");
            cancel.addActionListener(e -> runner.cancel());
            panel.add(buttons(cancel), BorderLayout.SOUTH);
            install(action.process().plan().title(), panel);
            runner.run(action.process(), outcome -> SwingUtilities.invokeLater(
                    () -> completed(outcome)), error -> SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(owner, "Process failed: " + error.getMessage());
                        dialog.dispose();
                    }));
        }

        void completed(ProcessOutcome<R> outcome) {
            if (outcome == null || outcome.result() == null) {
                String message = outcome != null && outcome.error() != null
                        ? outcome.error().getMessage() : "No result was produced.";
                JOptionPane.showMessageDialog(owner, message);
                dialog.dispose();
                return;
            }
            state.results();
            showResults(action.results(outcome), outcome.status());
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
            for (ProcessWorkflowResults.Tab<D> tab : results.tabs()) {
                List<Viewable> views = tab.cards().stream().map(ProcessWorkflowResults.Card::view).toList();
                java.util.Map<Viewable, ProcessWorkflowResults.Card<D>> cards =
                        new java.util.IdentityHashMap<>();
                tab.cards().forEach(card -> cards.put(card.view(), card));
                JComponent content = views.isEmpty() ? new JLabel("  (none)")
                        : SearchableView.builder(views).sample(views.get(0))
                                .mode(RenderingMode.CARD).collapsible(false).columns(2)
                                .cardDecorator(view -> cards.get(view).decoration().get())
                                .selectionListener(value -> {
                                    ProcessWorkflowResults.Card<D> card = value instanceof Viewable view
                                            ? cards.get(view) : null;
                                    selected.set(card);
                                    applySelected.setEnabled(card != null
                                            && card.decision().get() != null);
                                }).build();
                tabs.addTab(tab.title() + " (" + views.size() + ")", content);
            }
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
