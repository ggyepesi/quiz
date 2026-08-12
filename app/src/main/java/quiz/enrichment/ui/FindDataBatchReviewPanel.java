package quiz.enrichment.ui;

import objectview.Viewable;
import objectview.render.RenderingMode;
import objectview.view.SearchableView;
import quiz.enrichment.BatchReviewDecision;
import quiz.enrichment.EnrichmentDecision;
import quiz.enrichment.EnrichmentProposal;
import quiz.transform.DynamicViewable;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Virtualized card review over every proposal produced by Load values. */
public final class FindDataBatchReviewPanel {

    private FindDataBatchReviewPanel() { }

    public static void showDialog(
            Component owner, String title, String prompt,
            List<EnrichmentProposal> proposals, Consumer<BatchReviewDecision> onDone) {
        show(owner, title, prompt, proposals, Dialog.ModalityType.APPLICATION_MODAL, onDone);
    }

    public static JDialog showModeless(
            Component owner, String title, String prompt,
            List<EnrichmentProposal> proposals, Consumer<BatchReviewDecision> onDone) {
        return show(owner, title, prompt, proposals, Dialog.ModalityType.MODELESS, onDone);
    }

    private static JDialog show(
            Component owner, String title, String prompt,
            List<EnrichmentProposal> proposals, Dialog.ModalityType modality,
            Consumer<BatchReviewDecision> onDone) {
        Map<String, EnrichmentDecision> applicable = new LinkedHashMap<>();
        List<EnrichmentDecision> safeDefaults = new ArrayList<>();
        List<Viewable> cards = new ArrayList<>();
        int ordinal = 0;
        for (EnrichmentProposal proposal : proposals == null
                ? List.<EnrichmentProposal>of() : proposals) {
            String id = cardId(proposal, ordinal++);
            EnrichmentDecision decision = EnrichmentDecision.acceptDefault(proposal);
            boolean overwrite = overwrites(proposal);
            DynamicViewable card = new DynamicViewable(id, proposal.subject().displayName());
            card.type(decision == null ? "Not found" : overwrite ? "Overwrite" : "Found");
            card.put("Outcome", card.typeName());
            card.put("Proposed change", detail(proposal));
            cards.add(card);
            if (decision != null) {
                applicable.put(id, decision);
                if (!overwrite) safeDefaults.add(decision);
            }
        }

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.add(new JLabel("<html>" + CategorizedReviewPanel.html(prompt)
                + "<br><br>Select a card to stage that proposed value. Overwrites are "
                + "never included in Stage all safe results.</html>"), BorderLayout.NORTH);
        JButton close = new JButton("Close without staging");
        JButton selected = new JButton("Stage selected card");
        selected.setEnabled(false);
        JButton all = new JButton("Stage all safe results (" + safeDefaults.size() + ")");
        all.setEnabled(!safeDefaults.isEmpty());
        AtomicReference<EnrichmentDecision> selectedDecision = new AtomicReference<>();
        if (!cards.isEmpty()) {
            panel.add(SearchableView.builder(cards)
                    .sample(cards.get(0)).mode(RenderingMode.CARD)
                    .collapsible(false).columns(2)
                    .selectionListener(value -> {
                        EnrichmentDecision decision = value instanceof Viewable card
                                ? applicable.get(card.getIdentifier()) : null;
                        selectedDecision.set(decision);
                        selected.setEnabled(decision != null);
                    }).build(), BorderLayout.CENTER);
        }
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        footer.add(close); footer.add(selected); footer.add(all);
        panel.add(footer, BorderLayout.SOUTH);
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(owner), title, modality);
        dialog.setContentPane(panel); dialog.setSize(new Dimension(900, 680));
        dialog.setLocationRelativeTo(owner);
        close.addActionListener(e -> finish(dialog, onDone, List.of()));
        selected.addActionListener(e -> {
            EnrichmentDecision decision = selectedDecision.get();
            finish(dialog, onDone, decision == null ? List.of() : List.of(decision));
        });
        all.addActionListener(e -> finish(dialog, onDone, safeDefaults));
        dialog.setVisible(true);
        return dialog;
    }

    private static void finish(JDialog dialog, Consumer<BatchReviewDecision> callback,
                               List<EnrichmentDecision> decisions) {
        dialog.dispose();
        callback.accept(new BatchReviewDecision(List.copyOf(decisions)));
    }

    private static String cardId(EnrichmentProposal proposal, int ordinal) {
        return proposal.subject().type() + '\u0000' + proposal.subject().targetId()
                + '\u0000' + ordinal;
    }

    private static boolean overwrites(EnrichmentProposal proposal) {
        return proposal.fields().stream().anyMatch(
                field -> field.suggestedAction() == EnrichmentProposal.ReviewAction.REPLACE);
    }

    private static String detail(EnrichmentProposal proposal) {
        EnrichmentDecision accepted = EnrichmentDecision.acceptDefault(proposal);
        if (accepted != null && !accepted.fields().isEmpty()) {
            EnrichmentProposal.FieldCandidate field = accepted.fields().get(0).candidate();
            return field.field() + " = " + field.proposedValue() + sourceSuffix(field.source());
        }
        if (!proposal.media().isEmpty()) {
            EnrichmentProposal.MediaCandidate media = proposal.media().get(0);
            return media.field() + ": image" + sourceSuffix(media.source());
        }
        if (!proposal.fields().isEmpty()) {
            EnrichmentProposal.FieldCandidate field = proposal.fields().get(0);
            return field.field() + " rejected: " + field.compatibilityError()
                    + sourceSuffix(field.source());
        }
        return "No value found";
    }

    private static String sourceSuffix(EnrichmentProposal.SourceRef source) {
        if (source == null || source.kind() == null || source.kind().isBlank()) return "";
        String property = source.propertyId() == null || source.propertyId().isBlank()
                ? "" : ", " + source.propertyId();
        return " [" + source.kind() + property + "]";
    }
}
