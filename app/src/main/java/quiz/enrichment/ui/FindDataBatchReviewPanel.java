package quiz.enrichment.ui;

import quiz.enrichment.BatchReviewDecision;
import quiz.enrichment.EnrichmentDecision;
import quiz.enrichment.EnrichmentProposal;

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
import javax.swing.SwingUtilities;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
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
 * One approve/skip step over every member's proposal: a checkable row per member showing
 * the value (or image) that would be applied. Fine-grained per-field editing stays in the
 * single-member {@link EnrichmentReviewPanel}; this is the bulk accept for a batch fill,
 * so a "fill population for 30 countries" run is one screen, not 30 dialogs.
 */
public final class FindDataBatchReviewPanel extends JPanel {

    /** Show the batch review modally; {@code onDone} receives the accepted decisions
     *  (empty on cancel). */
    public static void showDialog(
            Component owner,
            String title,
            String prompt,
            List<EnrichmentProposal> proposals,
            Consumer<BatchReviewDecision> onDone) {
        createDialog(owner, title, prompt, proposals, onDone,
                Dialog.ModalityType.APPLICATION_MODAL).setVisible(true);
    }

    public static JDialog showModeless(
            Component owner, String title, String prompt,
            List<EnrichmentProposal> proposals, Consumer<BatchReviewDecision> onDone) {
        JDialog dialog = createDialog(owner, title, prompt, proposals, onDone,
                Dialog.ModalityType.MODELESS);
        dialog.setVisible(true);
        return dialog;
    }

    private static JDialog createDialog(
            Component owner, String title, String prompt,
            List<EnrichmentProposal> proposals, Consumer<BatchReviewDecision> onDone,
            Dialog.ModalityType modality) {
        Window window = quiz.ui.Dialogs.owner(owner);
        JDialog dialog = new JDialog(window, title, modality);
        quiz.ui.Dialogs.raiseOnOpen(dialog);
        Consumer<BatchReviewDecision> finish =
                quiz.ui.Dialogs.completion(dialog, onDone);
        FindDataBatchReviewPanel panel =
                new FindDataBatchReviewPanel(prompt, proposals, finish);
        quiz.ui.Dialogs.completeOnClose(dialog, finish,
                () -> new BatchReviewDecision(List.of()));
        dialog.add(panel);
        dialog.setSize(720, 560);
        dialog.setLocationRelativeTo(owner);
        return dialog;
    }

    private record Row(EnrichmentProposal proposal, JCheckBox accept) { }

    private final List<Row> rows = new ArrayList<>();
    // Rows per tab, so "Select all/none in tab" act on the VISIBLE tab only.
    private final Map<Component, List<Row>> rowsByTab = new LinkedHashMap<>();
    private JTabbedPane groups;

    private FindDataBatchReviewPanel(
            String prompt,
            List<EnrichmentProposal> proposals,
            Consumer<BatchReviewDecision> onApprove) {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Same skeleton as Resolve identities: FOUND (compatible) values are accepted by
        // default; NOT-APPLICABLE ones stay visible but unselectable so a schema mismatch is
        // explicit rather than looking like missing data. No "ambiguous" tab — Find Data
        // proposes one value per member, not a choice of candidates.
        List<EnrichmentProposal> found = new ArrayList<>();
        List<EnrichmentProposal> notApplicable = new ArrayList<>();
        for (EnrichmentProposal proposal : proposals == null
                ? List.<EnrichmentProposal>of() : proposals) {
            if (EnrichmentDecision.acceptDefault(proposal) != null) found.add(proposal);
            else notApplicable.add(proposal);
        }

        add(new JLabel("<html>" + html(prompt) + "</html>"), BorderLayout.NORTH);

        groups = new JTabbedPane();
        groups.addTab("Found (" + found.size() + ")",
                sectionPanel("Found values — accepted; uncheck any you don't want",
                        found, true));
        groups.addTab("Not applicable (" + notApplicable.size() + ")",
                sectionPanel("Rejected by field type / no value found", notApplicable, false));
        add(groups, BorderLayout.CENTER);
        add(buttons(onApprove), BorderLayout.SOUTH);
    }

    /** One group as an independently scrollable result panel — mirrors the Resolve
     *  identities section layout. */
    private JComponent sectionPanel(
            String title, List<EnrichmentProposal> items, boolean selectable) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JLabel heading = new JLabel(title);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        GridBagConstraints hc = gbc(0, new Insets(2, 4, 8, 4));
        panel.add(heading, hc);

        int firstRow = rows.size();
        if (items.isEmpty()) {
            JLabel none = new JLabel("(none)");
            none.setForeground(Color.GRAY);
            panel.add(none, gbc(1, new Insets(2, 4, 2, 4)));
        } else {
            int y = 1;
            for (EnrichmentProposal proposal : items) {
                String label = rowLabel(proposal);
                // A REPLACE would overwrite an existing (possibly curated) value, so it is
                // NOT pre-checked: overwriting is always a deliberate tick.
                boolean overwrite = overwrites(proposal);
                JCheckBox accept = new JCheckBox(
                        truncate(label, 90) + (overwrite ? "  [overwrites existing]" : ""),
                        selectable && !overwrite);
                accept.setEnabled(selectable);
                accept.setToolTipText(overwrite
                        ? label + " — would replace the current value; check to apply"
                        : label);
                panel.add(accept, gbc(y++, new Insets(1, 4, 1, 4)));
                rows.add(new Row(proposal, accept));
            }
        }

        GridBagConstraints filler = gbc(items.size() + 2, new Insets(0, 0, 0, 0));
        filler.weighty = 1.0;
        filler.fill = GridBagConstraints.BOTH;
        panel.add(Box.createGlue(), filler);

        JScrollPane scroll = new JScrollPane(panel);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        rowsByTab.put(scroll, new ArrayList<>(rows.subList(firstRow, rows.size())));
        return scroll;
    }

    private GridBagConstraints gbc(int y, Insets pad) {
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = y;
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.WEST;
        gc.gridwidth = GridBagConstraints.REMAINDER;
        gc.insets = pad;
        return gc;
    }

    private JComponent buttons(Consumer<BatchReviewDecision> onApprove) {
        JButton all = new JButton("Select all in tab");
        all.addActionListener(e -> currentTabRows().forEach(
                r -> { if (r.accept().isEnabled()) r.accept().setSelected(true); }));
        JButton none = new JButton("Select none in tab");
        none.addActionListener(e -> currentTabRows().forEach(r -> r.accept().setSelected(false)));
        JButton cancel = new JButton("Close without applying");
        cancel.addActionListener(e -> onApprove.accept(new BatchReviewDecision(List.of())));
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

    /** Rows of the currently visible tab — the scope of "Select all/none in tab".
     *  (Apply still collects selected rows across ALL tabs.) */
    private List<Row> currentTabRows() {
        if (groups == null) return rows;
        return rowsByTab.getOrDefault(groups.getSelectedComponent(), List.of());
    }

    private BatchReviewDecision collect() {
        List<EnrichmentDecision> accepted = new ArrayList<>();
        for (Row row : rows) {
            if (!row.accept().isSelected()) continue;
            EnrichmentDecision decision = EnrichmentDecision.acceptDefault(row.proposal());
            if (decision != null) accepted.add(decision);
        }
        return new BatchReviewDecision(accepted);
    }

    /** A proposal overwrites existing data when any of its field candidates is a REPLACE. */
    private static boolean overwrites(EnrichmentProposal proposal) {
        return proposal.fields().stream().anyMatch(
                f -> f.suggestedAction() == EnrichmentProposal.ReviewAction.REPLACE);
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static String rowLabel(EnrichmentProposal proposal) {
        String detail;
        if (!proposal.fields().isEmpty()) {
            EnrichmentProposal.FieldCandidate field = proposal.fields().get(0);
            detail = field.compatible()
                    ? field.field() + " = " + field.proposedValue()
                    : field.field() + " rejected: " + field.compatibilityError();
        } else if (!proposal.media().isEmpty()) {
            detail = proposal.media().get(0).field() + ": image";
        } else {
            detail = "(no value)";
        }
        return proposal.subject().displayName() + "  —  " + detail;
    }

    private static String html(String value) {
        return value == null ? ""
                : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
