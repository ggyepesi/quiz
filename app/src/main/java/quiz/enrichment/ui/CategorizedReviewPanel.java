package quiz.enrichment.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The shared "review a batch of proposals, accept a subset, apply" surface, used by both
 * identity resolution and data curation (Find Data): an HTML prompt, a {@link JTabbedPane}
 * of scrollable confidence/outcome <b>sections</b>, each a column of accept-checkbox
 * <b>rows</b>, and a button bar (Select all/none in the visible tab · Close · Apply). Apply
 * returns the payloads of the checked rows.
 *
 * <p>Only the row <em>content</em> and the accept/collect <em>semantics</em> differ between
 * callers, so those stay in the caller: it builds each {@link Row} (a payload + its accept
 * checkbox + the cells to show) and maps the accepted payloads back to its own decision.
 * Everything structural — tabs, per-section layout, tab-scoped select-all, dialog plumbing —
 * lives here so the two flows can't drift apart (and both get the same "No match / Not found"
 * treatment).
 *
 * @param <T> the caller's per-row payload (an identity instance, an enrichment proposal, …)
 */
public final class CategorizedReviewPanel<T> extends JPanel {

    /** One cell of a row, laid out after the accept checkbox. A {@code stretch} cell takes
     *  the remaining width (fill horizontally); the rest keep their preferred size. */
    public record Cell(JComponent component, boolean stretch) {
        public static Cell of(JComponent component) { return new Cell(component, false); }
        public static Cell stretch(JComponent component) { return new Cell(component, true); }
    }

    /** One review row: the caller's {@code payload}, its {@code accept} checkbox (the caller
     *  sets the initial checked/enabled state and any label), and the {@code cells} to show
     *  after the checkbox. */
    public static final class Row<T> {
        final T payload;
        final JCheckBox accept;
        final List<Cell> cells;

        public Row(T payload, JCheckBox accept, Cell... cells) {
            this.payload = payload;
            this.accept = accept;
            this.cells = List.of(cells);
        }
    }

    /** One tab: a {@code title} (with its count), a bold {@code heading} line, and its rows. */
    public record Section<T>(String title, String heading, List<Row<T>> rows) { }

    private final List<Row<T>> allRows = new ArrayList<>();
    // Rows per tab component, so "Select all/none in tab" acts on the VISIBLE tab only —
    // a flat select-all across e.g. Confident + Ambiguous is exactly the surprise to avoid.
    private final Map<Component, List<Row<T>>> rowsByTab = new LinkedHashMap<>();
    private final JTabbedPane groups = new JTabbedPane();

    private CategorizedReviewPanel(
            String prompt, List<Section<T>> sections, Consumer<List<T>> onApply) {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        add(new JLabel("<html>" + html(prompt) + "</html>"), BorderLayout.NORTH);
        for (Section<T> section : sections) {
            groups.addTab(section.title() + " (" + section.rows().size() + ")",
                    sectionPanel(section));
        }
        add(groups, BorderLayout.CENTER);
        add(buttons(onApply), BorderLayout.SOUTH);
    }

    private JComponent sectionPanel(Section<T> section) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JLabel heading = new JLabel(section.heading());
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        panel.add(heading, rowSpanning(0, new Insets(2, 4, 8, 4)));

        int firstRow = allRows.size();
        if (section.rows().isEmpty()) {
            JLabel none = new JLabel("(none)");
            none.setForeground(Color.GRAY);
            panel.add(none, rowSpanning(1, new Insets(2, 4, 2, 4)));
        } else {
            int y = 1;
            for (Row<T> row : section.rows()) {
                layoutRow(panel, row, y++);
                allRows.add(row);
            }
        }

        GridBagConstraints filler = rowSpanning(section.rows().size() + 2, new Insets(0, 0, 0, 0));
        filler.weighty = 1.0;
        filler.fill = GridBagConstraints.BOTH;
        panel.add(Box.createGlue(), filler);

        JScrollPane scroll = new JScrollPane(panel);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        rowsByTab.put(scroll, new ArrayList<>(allRows.subList(firstRow, allRows.size())));
        return scroll;
    }

    private void layoutRow(JPanel grid, Row<T> row, int y) {
        Insets pad = new Insets(1, 4, 1, 4);
        grid.add(row.accept, cell(0, y, 0, GridBagConstraints.NONE, pad));
        int x = 1;
        boolean anyStretch = row.cells.stream().anyMatch(Cell::stretch);
        for (Cell c : row.cells) {
            grid.add(c.component(), cell(x++, y,
                    c.stretch() ? 1.0 : 0,
                    c.stretch() ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE, pad));
        }
        if (!anyStretch) {
            grid.add(Box.createHorizontalGlue(),
                    cell(x, y, 1.0, GridBagConstraints.HORIZONTAL, pad));
        }
    }

    private JComponent buttons(Consumer<List<T>> onApply) {
        JButton all = new JButton("Select all in tab");
        all.addActionListener(e -> currentTabRows().forEach(
                r -> { if (r.accept.isEnabled()) r.accept.setSelected(true); }));
        JButton none = new JButton("Select none in tab");
        none.addActionListener(e -> currentTabRows().forEach(r -> r.accept.setSelected(false)));
        JButton cancel = new JButton("Close without applying");
        cancel.addActionListener(e -> onApply.accept(List.of()));
        JButton apply = new JButton("Apply selected");
        apply.addActionListener(e -> onApply.accept(accepted()));

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        panel.add(all);
        panel.add(none);
        panel.add(Box.createHorizontalStrut(12));
        panel.add(cancel);
        panel.add(apply);
        return panel;
    }

    /** Rows of the currently visible tab — the scope of "Select all/none in tab".
     *  (Apply still collects checked rows across ALL tabs.) */
    private List<Row<T>> currentTabRows() {
        return rowsByTab.getOrDefault(groups.getSelectedComponent(), List.of());
    }

    private List<T> accepted() {
        List<T> out = new ArrayList<>();
        for (Row<T> row : allRows) {
            if (row.accept.isSelected()) {
                out.add(row.payload);
            }
        }
        return out;
    }

    private static GridBagConstraints cell(int x, int y, double weightx, int fill, Insets pad) {
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = x;
        gc.gridy = y;
        gc.weightx = weightx;
        gc.fill = fill;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = pad;
        return gc;
    }

    private static GridBagConstraints rowSpanning(int y, Insets pad) {
        GridBagConstraints gc = cell(0, y, 1.0, GridBagConstraints.HORIZONTAL, pad);
        gc.gridwidth = GridBagConstraints.REMAINDER;
        return gc;
    }

    // --- Dialog plumbing shared by both callers (one-shot completion, close = cancel) ---

    /** Show the review modally; {@code onAccepted} receives the checked rows' payloads
     *  (empty on cancel/close). */
    public static <T> void showDialog(
            Component owner, String title, String prompt,
            List<Section<T>> sections, Dimension size, Consumer<List<T>> onAccepted) {
        createDialog(owner, title, prompt, sections, size, onAccepted,
                Dialog.ModalityType.APPLICATION_MODAL).setVisible(true);
    }

    /** Show without blocking any application window; completion still occurs exactly once. */
    public static <T> JDialog showModeless(
            Component owner, String title, String prompt,
            List<Section<T>> sections, Dimension size, Consumer<List<T>> onAccepted) {
        JDialog dialog = createDialog(owner, title, prompt, sections, size, onAccepted,
                Dialog.ModalityType.MODELESS);
        dialog.setVisible(true);
        return dialog;
    }

    private static <T> JDialog createDialog(
            Component owner, String title, String prompt,
            List<Section<T>> sections, Dimension size, Consumer<List<T>> onAccepted,
            Dialog.ModalityType modality) {
        Window window = quiz.ui.Dialogs.owner(owner);
        JDialog dialog = new JDialog(window, title, modality);
        quiz.ui.Dialogs.raiseOnOpen(dialog);
        Consumer<List<T>> finish = quiz.ui.Dialogs.completion(dialog, onAccepted);
        dialog.add(new CategorizedReviewPanel<>(prompt, sections, finish));
        quiz.ui.Dialogs.completeOnClose(dialog, finish, List::of);
        dialog.setSize(size);
        dialog.setLocationRelativeTo(owner);
        return dialog;
    }

    // --- Small text utilities both callers use when building row cells ---

    public static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    public static String html(String value) {
        return value == null ? ""
                : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
