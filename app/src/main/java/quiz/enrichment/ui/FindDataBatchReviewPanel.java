package quiz.enrichment.ui;

import quiz.enrichment.BatchReviewDecision;
import quiz.enrichment.EnrichmentDecision;
import quiz.enrichment.EnrichmentProposal;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
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
        Consumer<BatchReviewDecision> handler = onDone == null ? ignored -> { } : onDone;
        java.util.concurrent.atomic.AtomicBoolean completed =
                new java.util.concurrent.atomic.AtomicBoolean();
        Consumer<BatchReviewDecision> finish = decision -> {
            if (completed.compareAndSet(false, true)) {
                handler.accept(decision);
                dialog.dispose();
            }
        };
        FindDataBatchReviewPanel panel =
                new FindDataBatchReviewPanel(prompt, proposals, finish);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                finish.accept(new BatchReviewDecision(List.of()));
            }
        });
        dialog.add(panel);
        dialog.setSize(720, 560);
        dialog.setLocationRelativeTo(owner);
        return dialog;
    }

    private final Map<EnrichmentProposal, JCheckBox> rows = new LinkedHashMap<>();

    private FindDataBatchReviewPanel(
            String prompt,
            List<EnrichmentProposal> proposals,
            Consumer<BatchReviewDecision> onApprove) {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Compatible proposals are reviewable; incompatible source values remain visible
        // as disabled rows so a schema mismatch is explicit rather than looking like
        // missing data.
        List<EnrichmentProposal> applicable = new ArrayList<>();
        for (EnrichmentProposal proposal : proposals) {
            if (EnrichmentDecision.acceptDefault(proposal) != null) {
                applicable.add(proposal);
            }
        }

        int incompatible = proposals == null ? 0 : proposals.size() - applicable.size();
        add(new JLabel("<html>" + html(prompt) + " &nbsp;<i>(" + applicable.size()
                + " compatible proposal(s)" + (incompatible == 0 ? ""
                : ", " + incompatible + " rejected by field type")
                + ")</i></html>"), BorderLayout.NORTH);

        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        for (EnrichmentProposal proposal : proposals == null
                ? List.<EnrichmentProposal>of() : proposals) {
            boolean accepted = applicable.contains(proposal);
            JCheckBox box = new JCheckBox(rowLabel(proposal), accepted);
            box.setEnabled(accepted);
            if (accepted) rows.put(proposal, box);
            list.add(box);
        }
        add(new JScrollPane(list), BorderLayout.CENTER);
        add(buttons(onApprove), BorderLayout.SOUTH);
    }

    private JComponent buttons(Consumer<BatchReviewDecision> onApprove) {
        JButton all = new JButton("Select all");
        all.addActionListener(e -> rows.values().forEach(box -> box.setSelected(true)));
        JButton none = new JButton("Select none");
        none.addActionListener(e -> rows.values().forEach(box -> box.setSelected(false)));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> onApprove.accept(new BatchReviewDecision(List.of())));
        JButton apply = new JButton("Apply selected");
        apply.addActionListener(e -> onApprove.accept(collect()));

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        panel.add(all);
        panel.add(none);
        panel.add(cancel);
        panel.add(apply);
        return panel;
    }

    private BatchReviewDecision collect() {
        List<EnrichmentDecision> accepted = new ArrayList<>();
        rows.forEach((proposal, box) -> {
            if (box.isSelected()) {
                EnrichmentDecision decision = EnrichmentDecision.acceptDefault(proposal);
                if (decision != null) {
                    accepted.add(decision);
                }
            }
        });
        return new BatchReviewDecision(accepted);
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
