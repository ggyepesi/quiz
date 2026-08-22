package wikidata.explore.workbench;

import work.Query;
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

    /** Infobox identity is structured: keep Template.parameter as the durable selection
     * key, but present the template title and parameter separately to the reader. The
     * key is split by the grammar's owner, so the card cannot describe a different key
     * than the acquisition and the provider will read. */
    static List<DiscoveredValueView> infoboxRows(List<List<Object>> rows) {
        List<DiscoveredValueView> result = new ArrayList<>();
        for (List<Object> row : rows == null ? List.<List<Object>>of() : rows) {
            String text = cell(row, 0);
            datasource.evidence.InfoboxParameters.Key key =
                    datasource.evidence.InfoboxParameters.Key.parse(text);
            if (key == null) continue;
            int have;
            try { have = Integer.parseInt(cell(row, 1)); }
            catch (NumberFormatException ignored) { have = 0; }
            result.add(new DiscoveredValueView(text, key.parameter(), key.template(),
                    have, cell(row, 2)));
        }
        return List.copyOf(result);
    }

    /** With one source article, "have = 1" and repeating that article's title on every
     * card are invariants of the request, not properties of a candidate. Do not make the
     * reader scan two identical fields on every result. Other discoveries retain the
     * metadata whenever it distinguishes candidates.
     *
     * <p>A field every candidate leaves EMPTY is redundant for the same reason one they
     * all fill identically is; the earlier rule kept the blank one and rendered an empty
     * row on every card. Nothing is redundant among fewer than two candidates, though:
     * "they all agree" is vacuous for one card, and hiding on it strips the only result
     * of everything but its title. */
    static java.util.Set<String> redundantMetadata(List<DiscoveredValueView> values) {
        if (values == null || values.size() < 2) return java.util.Set.of();
        java.util.Set<String> redundant = new java.util.LinkedHashSet<>();
        if (values.stream().allMatch(value -> value.have() == 1)) redundant.add("have");
        String example = values.getFirst().examples();
        if (values.stream().allMatch(value -> example.equals(value.examples()))) {
            redundant.add("examples");
        }
        return java.util.Set.copyOf(redundant);
    }

    /** The card renders its display name as a header, so the value field beneath it would
     * say the same thing twice — except that a card whose every other field is redundant
     * would then have an empty body. So the twin is kept ONLY as the body of last resort,
     * and dropped as soon as anything else is left to render, or as soon as it differs
     * from the title (an infobox card shows the parameter, the header the whole key). */
    static java.util.Set<String> hiddenFields(List<DiscoveredValueView> values) {
        java.util.Set<String> hidden = new java.util.LinkedHashSet<>(java.util.Set.of("value"));
        java.util.Set<String> redundant = redundantMetadata(values);
        hidden.addAll(redundant);
        boolean noStructure = values == null || values.stream().allMatch(
                value -> value.sourceStructure().isBlank());
        if (noStructure) hidden.add("sourceStructure");
        boolean repeatsTitle = values == null || values.stream().allMatch(
                value -> value.discoveredValue().equals(value.value()));
        boolean bodyWouldBeEmpty = noStructure
                && redundant.contains("have") && redundant.contains("examples");
        if (repeatsTitle && !bodyWouldBeEmpty) hidden.add("discoveredValue");
        return java.util.Set.copyOf(hidden);
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
        // The value is the card's own title; hiddenFields decides what may repeat it.
        JComponent cards = objectview.view.SearchableView.builder(values)
                .sample(values.getFirst()).hiddenFields(hiddenFields(values))
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
