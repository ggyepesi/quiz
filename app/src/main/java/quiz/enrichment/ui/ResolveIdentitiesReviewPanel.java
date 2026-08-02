package quiz.enrichment.ui;

import quiz.enrichment.ResolveIdentitiesDecision;
import quiz.enrichment.ResolveIdentitiesReviewRequest.IdentityMatch;
import quiz.enrichment.ResolveIdentitiesReviewRequest.InstanceIdentity;
import wikidata.explore.workbench.WikidataLinks;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
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
import java.util.List;
import java.util.function.Consumer;

/**
 * One review over identity candidates, grouped by match confidence so attention goes where
 * it's needed: (a) NAME MATCH — a candidate's label matches the instance name;
 * (b) AMBIGUOUS — candidates found but none is an exact-name match (unchecked, pick one);
 * (c) NO MATCH — the search returned nothing. Rows are single-line (truncated + tooltip) so
 * long names stay readable.
 */
public final class ResolveIdentitiesReviewPanel extends JPanel {

    public static void showDialog(
            Component owner,
            String title,
            String prompt,
            List<InstanceIdentity> instances,
            Consumer<ResolveIdentitiesDecision> onDone) {
        createDialog(owner, title, prompt, instances, List.of(), onDone,
                Dialog.ModalityType.APPLICATION_MODAL).setVisible(true);
    }

    /** Show without blocking any application window; completion still occurs exactly once. */
    public static JDialog showModeless(
            Component owner,
            String title,
            String prompt,
            List<InstanceIdentity> instances,
            Consumer<ResolveIdentitiesDecision> onDone) {
        return showModeless(owner, title, prompt, instances, List.of(), onDone);
    }

    /** Re-open a retained result and mark assignments already applied in memory. */
    public static JDialog showModeless(
            Component owner,
            String title,
            String prompt,
            List<InstanceIdentity> instances,
            List<ResolveIdentitiesDecision.Resolved> applied,
            Consumer<ResolveIdentitiesDecision> onDone) {
        JDialog dialog = createDialog(owner, title, prompt, instances, applied, onDone,
                Dialog.ModalityType.MODELESS);
        dialog.setVisible(true);
        return dialog;
    }

    private static JDialog createDialog(
            Component owner,
            String title,
            String prompt,
            List<InstanceIdentity> instances,
            List<ResolveIdentitiesDecision.Resolved> applied,
            Consumer<ResolveIdentitiesDecision> onDone,
            Dialog.ModalityType modality) {
        Window window = quiz.ui.Dialogs.owner(owner);
        JDialog dialog = new JDialog(window, title, modality);
        quiz.ui.Dialogs.raiseOnOpen(dialog);
        Consumer<ResolveIdentitiesDecision> handler = onDone == null ? ignored -> { } : onDone;
        java.util.concurrent.atomic.AtomicBoolean completed =
                new java.util.concurrent.atomic.AtomicBoolean();
        Consumer<ResolveIdentitiesDecision> finish = decision -> {
            if (completed.compareAndSet(false, true)) {
                handler.accept(decision);
                dialog.dispose();
            }
        };
        ResolveIdentitiesReviewPanel panel =
                new ResolveIdentitiesReviewPanel(prompt, instances, applied, finish);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                finish.accept(new ResolveIdentitiesDecision(List.of()));
            }
        });
        dialog.add(panel);
        dialog.setSize(840, 660);
        dialog.setLocationRelativeTo(owner);
        return dialog;
    }

    private record Row(
            InstanceIdentity instance, JCheckBox accept, JComboBox<IdentityMatch> combo) { }

    private final List<Row> rows = new ArrayList<>();
    // Rows per tab component, so "Select all/none" act on the VISIBLE tab only —
    // a flat select-all across Confident + Ambiguous is exactly the surprise to avoid.
    private final java.util.Map<Component, List<Row>> rowsByTab =
            new java.util.LinkedHashMap<>();
    private JTabbedPane groups;

    private ResolveIdentitiesReviewPanel(
            String prompt,
            List<InstanceIdentity> instances,
            List<ResolveIdentitiesDecision.Resolved> applied,
            Consumer<ResolveIdentitiesDecision> onApprove) {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        List<InstanceIdentity> confident = new ArrayList<>();
        List<InstanceIdentity> ambiguous = new ArrayList<>();
        List<InstanceIdentity> noMatch = new ArrayList<>();
        for (InstanceIdentity instance : instances) {
            if (instance.candidates().isEmpty()) {
                noMatch.add(instance);
            } else if (exactMatch(instance) != null) {
                confident.add(instance);
            } else {
                ambiguous.add(instance);
            }
        }

        java.util.Map<String, ResolveIdentitiesDecision.Resolved> appliedByInstance =
                new java.util.LinkedHashMap<>();
        for (ResolveIdentitiesDecision.Resolved resolved
                : applied == null ? List.<ResolveIdentitiesDecision.Resolved>of() : applied) {
            appliedByInstance.put(key(resolved.type(), resolved.targetId()), resolved);
        }
        String appliedSummary = appliedByInstance.isEmpty() ? ""
                : "<br><b>" + appliedByInstance.size()
                        + " assignment(s) already applied in memory (not yet saved).</b>";
        add(new JLabel("<html>" + html(prompt) + appliedSummary + "</html>"),
                BorderLayout.NORTH);

        groups = new JTabbedPane();
        // A label match is useful ordering, not proof of entity type. Nothing is
        // pre-accepted until class/type constraints become part of candidate discovery.
        groups.addTab("Confident (" + confident.size() + ")",
                sectionPanel("Top exact name match — accepted; uncheck any wrong entity type",
                        confident, true, true, appliedByInstance));
        groups.addTab("Ambiguous (" + ambiguous.size() + ")",
                sectionPanel("Choose the right entity",
                        ambiguous, true, false, appliedByInstance));
        groups.addTab("No match (" + noMatch.size() + ")",
                sectionPanel("No match found",
                        noMatch, false, false, appliedByInstance));

        add(groups, BorderLayout.CENTER);
        add(buttons(onApprove), BorderLayout.SOUTH);
    }

    /** One confidence group as an independently scrollable result panel. */
    private JComponent sectionPanel(
            String title, List<InstanceIdentity> items,
            boolean selectable, boolean checkedByDefault,
            java.util.Map<String, ResolveIdentitiesDecision.Resolved> applied) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JLabel heading = new JLabel(title);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        GridBagConstraints headingConstraints =
                constraints(0, 0, 1.0, GridBagConstraints.HORIZONTAL,
                        new Insets(2, 4, 8, 4));
        headingConstraints.gridwidth = GridBagConstraints.REMAINDER;
        panel.add(heading, headingConstraints);

        int firstRow = rows.size();
        if (items.isEmpty()) {
            JLabel none = new JLabel("(none)");
            none.setForeground(Color.GRAY);
            GridBagConstraints gc = constraints(
                    0, 1, 1.0, GridBagConstraints.HORIZONTAL,
                    new Insets(2, 4, 2, 4));
            gc.gridwidth = GridBagConstraints.REMAINDER;
            panel.add(none, gc);
        } else {
            int[] y = {1};
            for (InstanceIdentity instance : items) {
                addRow(panel, y, instance, selectable, checkedByDefault,
                        applied.get(key(instance.type(), instance.targetId())));
            }
        }

        GridBagConstraints filler = constraints(
                0, items.size() + 2, 1.0, GridBagConstraints.BOTH,
                new Insets(0, 0, 0, 0));
        filler.gridwidth = GridBagConstraints.REMAINDER;
        filler.weighty = 1.0;
        panel.add(Box.createGlue(), filler);

        JScrollPane scroll = new JScrollPane(panel);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        rowsByTab.put(scroll,
                new ArrayList<>(rows.subList(firstRow, rows.size())));
        return scroll;
    }

    private void addRow(
            JPanel grid, int[] y, InstanceIdentity instance,
            boolean selectable, boolean checked,
            ResolveIdentitiesDecision.Resolved applied) {
        int row = y[0]++;
        Insets pad = new Insets(1, 4, 1, 4);

        boolean alreadyApplied = applied != null;
        JCheckBox accept = new JCheckBox(alreadyApplied ? "Applied" : "",
                alreadyApplied || selectable && checked && !instance.candidates().isEmpty());
        accept.setEnabled(!alreadyApplied && selectable && !instance.candidates().isEmpty());
        accept.setToolTipText(alreadyApplied ? "Already applied in memory" : null);
        if (alreadyApplied) {
            accept.setForeground(new Color(35, 125, 55));
            accept.setFont(accept.getFont().deriveFont(Font.BOLD));
        }
        place(grid, accept, 0, row, 0, GridBagConstraints.NONE, pad);

        String typedName = instance.name() + "  [" + instance.type() + "]";
        JLabel name = new JLabel(truncate(typedName, 34));
        name.setToolTipText(typedName);
        name.setPreferredSize(new Dimension(210, name.getPreferredSize().height));
        place(grid, name, 1, row, 0, GridBagConstraints.HORIZONTAL, pad);

        JComboBox<IdentityMatch> combo = null;
        if (!instance.candidates().isEmpty()) {
            List<IdentityMatch> candidates = new ArrayList<>(instance.candidates());
            if (alreadyApplied && candidates.stream()
                    .noneMatch(candidate -> applied.qid().equals(candidate.qid()))) {
                candidates.add(0, new IdentityMatch(
                        applied.qid(), applied.label(), "Applied in memory"));
            }
            combo = new JComboBox<>(candidates.toArray(new IdentityMatch[0]));
            combo.setRenderer(new MatchRenderer());
            combo.setPreferredSize(new Dimension(190, combo.getPreferredSize().height));
            IdentityMatch exact = exactMatch(instance);
            IdentityMatch appliedMatch = alreadyApplied
                    ? candidates.stream()
                            .filter(candidate -> applied.qid().equals(candidate.qid()))
                            .findFirst().orElse(null)
                    : null;
            combo.setSelectedItem(appliedMatch != null ? appliedMatch
                    : exact != null ? exact : instance.candidates().get(0));
            combo.setEnabled(!alreadyApplied);
            place(grid, combo, 2, row, 0, GridBagConstraints.NONE, pad);

            JComboBox<IdentityMatch> boundCombo = combo;
            JLabel qid = new JLabel();
            WikidataLinks.linkify(qid, () -> {
                Object selected = boundCombo.getSelectedItem();
                return selected instanceof IdentityMatch match ? match.qid() : null;
            });

            JLabel description = new JLabel();
            description.setForeground(Color.GRAY);
            Runnable showCandidate = () -> {
                Object selected = boundCombo.getSelectedItem();
                IdentityMatch match = selected instanceof IdentityMatch candidate
                        ? candidate : null;
                String selectedQid = match == null || match.qid() == null
                        ? "" : match.qid();
                qid.setText(selectedQid.isBlank()
                        ? ""
                        : "<html><u>" + html(selectedQid) + "</u></html>");
                qid.setToolTipText(selectedQid.isBlank()
                        ? null
                        : "Open " + selectedQid + " on Wikidata");

                String text = match == null ? "" : match.description();
                description.setText(truncate(text, 46));
                description.setToolTipText(text == null || text.isBlank() ? null : text);
            };
            combo.addActionListener(e -> showCandidate.run());
            showCandidate.run();
            place(grid, qid, 3, row, 0, GridBagConstraints.NONE, pad);
            place(grid, description, 4, row, 1.0, GridBagConstraints.HORIZONTAL, pad);
        } else {
            JLabel none = new JLabel("no candidates — resolve individually later");
            none.setForeground(Color.GRAY);
            GridBagConstraints gc = constraints(2, row, 1.0, GridBagConstraints.HORIZONTAL, pad);
            gc.gridwidth = 3;
            grid.add(none, gc);
        }
        rows.add(new Row(instance, accept, combo));
    }

    private static String key(String type, String targetId) {
        return String.valueOf(type) + '\u0000' + targetId;
    }

    private static void place(
            JPanel grid, Component component, int x, int y, double weightx, int fill, Insets pad) {
        grid.add(component, constraints(x, y, weightx, fill, pad));
    }

    private static GridBagConstraints constraints(
            int x, int y, double weightx, int fill, Insets pad) {
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = x;
        gc.gridy = y;
        gc.weightx = weightx;
        gc.fill = fill;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = pad;
        return gc;
    }

    private JComponent buttons(Consumer<ResolveIdentitiesDecision> onApprove) {
        JButton all = new JButton("Select all in tab");
        all.addActionListener(e -> currentTabRows().forEach(
                r -> { if (r.accept().isEnabled()) r.accept().setSelected(true); }));
        JButton none = new JButton("Select none in tab");
        none.addActionListener(e -> currentTabRows().forEach(
                r -> r.accept().setSelected(false)));
        JButton cancel = new JButton("Close without applying");
        cancel.addActionListener(
                e -> onApprove.accept(new ResolveIdentitiesDecision(List.of())));
        JButton apply = new JButton("Apply selected");
        apply.addActionListener(e -> onApprove.accept(collect()));

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        panel.add(all);
        panel.add(none);
        panel.add(Box.createHorizontalStrut(12));
        panel.add(cancel);
        panel.add(apply);
        return panel;
    }

    /** The rows of the currently visible tab — the scope of "Select all/none in tab".
     *  (Apply still collects selected rows across ALL tabs.) */
    private List<Row> currentTabRows() {
        if (groups == null) {
            return rows;
        }
        return rowsByTab.getOrDefault(groups.getSelectedComponent(), List.of());
    }

    private ResolveIdentitiesDecision collect() {
        List<ResolveIdentitiesDecision.Resolved> resolved = new ArrayList<>();
        for (Row row : rows) {
            if (row.combo() == null || !row.accept().isSelected()) {
                continue;
            }
            if (row.combo().getSelectedItem() instanceof IdentityMatch match) {
                resolved.add(new ResolveIdentitiesDecision.Resolved(
                        row.instance().type(), row.instance().targetId(),
                        match.qid(), match.label()));
            }
        }
        return new ResolveIdentitiesDecision(resolved);
    }

    /** The one candidate whose label matches the instance name — but only when it is the
     *  SOLE such match. Two name-matches (Georgia the country vs the U.S. state) is the
     *  ambiguous case, not a confident one, so it returns null. */
    /** The confident pick: the FIRST exact-label match. Candidates come back in Wikidata
     *  relevance order, so the top exact hit is the canonical entity ("India" → Q668).
     *  Homonyms sharing the label no longer demote it to ambiguous — ranking decides. */
    static IdentityMatch exactMatch(InstanceIdentity instance) {
        for (IdentityMatch match : instance.candidates()) {
            if (match.label() != null && instance.name() != null
                    && match.label().equalsIgnoreCase(instance.name())) {
                return match;
            }
        }
        return null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static String html(String value) {
        return value == null ? ""
                : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final class MatchRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof IdentityMatch match) {
                // Dropdown items (index >= 0) also carry the description, to tell same-labelled
                // candidates apart; the collapsed selected value (index -1) stays compact.
                String desc = index >= 0 && match.description() != null
                        && !match.description().isBlank()
                        ? "  — " + match.description() : "";
                setText(match.label() + "  (" + match.qid() + ")" + desc);
            } else {
                setText("(no match)");
            }
            return this;
        }
    }
}
