package wikidata.explore.workbench;

import wikidata.explore.query.core.Query;
import wikidata.explore.query.swing.SwingQueryRunner;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/** Shared UI skeleton for discovering a source capability from selected/sample seeds.
 * Providers own acquisition and the wording; this class owns the invariant workflow:
 * execute visibly, decode rows into {@link DiscoveredValueView}s, render them through
 * SearchableView, select, then accept — or dismiss. */
final class SourceDiscoveryPicker {
    private SourceDiscoveryPicker() { }

    record Spec<R>(
            String title,
            String explanation,
            String emptyMessage,
            String acceptVerb,
            Function<R, List<DiscoveredValueView>> values) { }

    /** The three things every discovery reports: the value, how much of the sample carries
     * it, and what it looked like there. One decoder, because a provider that answered
     * these differently would not be a discovery. */
    static List<DiscoveredValueView> rows(List<List<Object>> rows) {
        List<DiscoveredValueView> result = new ArrayList<>();
        for (List<Object> row : rows == null ? List.<List<Object>>of() : rows) {
            String value = cell(row, 0);
            if (value.isBlank()) continue;
            int have;
            try { have = Integer.parseInt(cell(row, 1)); }
            catch (NumberFormatException ignored) { have = 0; }
            result.add(new DiscoveredValueView(value, have, cell(row, 2)));
        }
        return List.copyOf(result);
    }

    private static String cell(List<Object> row, int at) {
        return row != null && at >= 0 && at < row.size() && row.get(at) != null
                ? String.valueOf(row.get(at)) : "";
    }

    /** {@code dismissed} runs whenever the caller gets no choice — nothing discovered,
     * the dialog closed, or discovery could not run at all. A caller whose button must
     * always end somewhere (an editor holding an already-configured rule) needs to hear
     * that; silently doing nothing is how a button becomes a dead control. */
    static <R> void run(Component parent, SwingQueryRunner runner, Query<R> query,
            Spec<R> spec, Consumer<DiscoveredValueView> accepted, Runnable dismissed) {
        if (runner == null || query == null || spec == null) {
            dismiss(dismissed);
            return;
        }
        if (runner.isRunning()) {
            // SwingQueryRunner.run deliberately ignores a second run. Complete the
            // caller's lifecycle instead of leaving an editor button waiting forever.
            dismiss(dismissed);
            return;
        }
        runner.run(query, result -> SwingUtilities.invokeLater(() ->
                        show(parent, spec, result, accepted, dismissed)),
                failure -> SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(parent,
                            spec.title() + " failed:\n" + failure.getMessage(),
                            "Discovery failed", JOptionPane.ERROR_MESSAGE);
                    dismiss(dismissed);
                }));
    }

    private static void dismiss(Runnable dismissed) {
        if (dismissed != null) dismissed.run();
    }

    private static <R> void show(Component parent, Spec<R> spec, R result,
            Consumer<DiscoveredValueView> accepted, Runnable dismissed) {
        List<DiscoveredValueView> values = spec.values().apply(result);
        if (values == null || values.isEmpty()) {
            JOptionPane.showMessageDialog(parent, spec.emptyMessage(), spec.title(),
                    JOptionPane.INFORMATION_MESSAGE);
            dismiss(dismissed);
            return;
        }
        JButton use = new JButton(spec.acceptVerb());
        use.setEnabled(false);
        AtomicReference<DiscoveredValueView> selected = new AtomicReference<>();
        // The value is the card's own title; repeating it as a field says it twice.
        JComponent cards = objectview.view.SearchableView.builder(values)
                .sample(values.getFirst()).hiddenFields(java.util.Set.of("value"))
                .collapsible(false).selectionListener(value -> {
                    DiscoveredValueView choice = value instanceof DiscoveredValueView view
                            ? view : null;
                    selected.set(choice);
                    use.setEnabled(choice != null);
                }).build();
        cards.setPreferredSize(new Dimension(720, 480));
        JDialog dialog = new JDialog(quiz.ui.Dialogs.owner(parent), spec.title(),
                Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout(0, 6));
        dialog.add(new JLabel("<html><div style='width: 680px'>"
                + spec.explanation() + "</div></html>"), BorderLayout.NORTH);
        dialog.add(cards, BorderLayout.CENTER);
        JButton close = new JButton("Close");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(use); actions.add(close); dialog.add(actions, BorderLayout.SOUTH);
        AtomicReference<DiscoveredValueView> chosen = new AtomicReference<>();
        use.addActionListener(event -> {
            DiscoveredValueView choice = selected.get();
            if (choice == null) return;
            chosen.set(choice);
            dialog.dispose();
        });
        close.addActionListener(event -> dialog.dispose());
        // One exit, so the window button, Close and Use are the same three outcomes:
        // a choice, or a dismissal the caller hears about.
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent event) {
                DiscoveredValueView choice = chosen.get();
                if (choice == null) dismiss(dismissed);
                else if (accepted != null) accepted.accept(choice);
            }
        });
        dialog.pack(); dialog.setLocationRelativeTo(parent); dialog.setVisible(true);
    }
}
