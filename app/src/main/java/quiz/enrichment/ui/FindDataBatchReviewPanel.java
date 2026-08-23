package quiz.enrichment.ui;

import datasource.SourceRef;

import objectview.Viewable;
import objectview.render.RenderingMode;
import objectview.view.SearchableView;
import quiz.enrichment.BatchReviewDecision;
import quiz.enrichment.EnrichmentDecision;
import datasource.enrichment.EnrichmentProposal;
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
        show(owner, title, prompt, proposals, List.of(),
                Dialog.ModalityType.APPLICATION_MODAL, onDone);
    }

    /**
     * @param sourceReport what each configured source yielded, as cards. Rendered
     *        alongside the proposals rather than in a separate window: "DBpedia was
     *        asked and had nothing" is the answer to why a member shows no value, and
     *        it belongs where the reader is already asking that.
     */
    public static JDialog showModeless(
            Component owner, String title, String prompt,
            List<EnrichmentProposal> proposals, List<Viewable> sourceReport,
            Consumer<BatchReviewDecision> onDone) {
        return show(owner, title, prompt, proposals, sourceReport,
                Dialog.ModalityType.MODELESS, onDone);
    }

    private static JDialog show(
            Component owner, String title, String prompt,
            List<EnrichmentProposal> proposals, List<Viewable> sourceReport,
            Dialog.ModalityType modality,
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
            card.type(EnrichmentDecision.requiresIdentityChoice(proposal)
                    ? "Needs identity choice"
                    : corroborationOnly(decision) ? "Corroborated"
                    : decision == null ? "Not found" : overwrite ? "Overwrite" : "Found");
            card.put("Outcome", card.typeName());
            card.put("Summary", detail(proposal));
            List<Viewable> changes = changeCards(proposal);
            if (!changes.isEmpty()) card.put("Proposed changes", changes);
            cards.add(card);
            if (decision != null) {
                applicable.put(id, decision);
                if (!overwrite) safeDefaults.add(decision);
            }
        }

        // Last, so the proposals a reader came to act on stay at the top; present even
        // when nothing was found, which is when the question they answer is asked.
        cards.addAll(sourceReport == null ? List.<Viewable>of() : sourceReport);

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
        JLabel outcome = new JLabel(" ");
        footer.add(outcome);
        footer.add(close); footer.add(selected); footer.add(all);
        panel.add(footer, BorderLayout.SOUTH);
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(owner), title, modality);
        dialog.setContentPane(panel); dialog.setSize(new Dimension(900, 680));
        dialog.setLocationRelativeTo(owner);
        close.addActionListener(e -> dialog.dispose());
        selected.addActionListener(e -> {
            EnrichmentDecision decision = selectedDecision.get();
            stage(onDone, decision == null ? List.of() : List.of(decision), outcome,
                    selected, all);
        });
        all.addActionListener(e -> stage(onDone, safeDefaults, outcome, selected, all));
        dialog.setVisible(true);
        return dialog;
    }

    private static void stage(Consumer<BatchReviewDecision> callback,
                              List<EnrichmentDecision> decisions, JLabel outcome,
                              JButton selected, JButton all) {
        if (decisions.isEmpty()) return;
        callback.accept(new BatchReviewDecision(List.copyOf(decisions)));
        outcome.setText("Staged " + decisions.size() + " result"
                + (decisions.size() == 1 ? "" : "s")
                + "; use Save staged changes in TransformApp to keep them.");
        selected.setEnabled(false);
        all.setEnabled(false);
    }

    private static String cardId(EnrichmentProposal proposal, int ordinal) {
        return proposal.subject().type() + '\u0000' + proposal.subject().targetId()
                + '\u0000' + ordinal;
    }

    private static boolean overwrites(EnrichmentProposal proposal) {
        EnrichmentDecision decision = EnrichmentDecision.acceptDefault(proposal);
        return decision != null && decision.fields().stream().anyMatch(
                field -> field.action() == EnrichmentProposal.ReviewAction.REPLACE);
    }

    private static boolean corroborationOnly(EnrichmentDecision decision) {
        return decision != null && decision.media() == null && !decision.fields().isEmpty()
                && decision.fields().stream().allMatch(field ->
                        field.action() == EnrichmentProposal.ReviewAction.CORROBORATE);
    }

    private static String detail(EnrichmentProposal proposal) {
        if (EnrichmentDecision.requiresIdentityChoice(proposal)) {
            return "Multiple records from the same source are referenced; choose an identity "
                    + "before applying.";
        }
        EnrichmentDecision accepted = EnrichmentDecision.acceptDefault(proposal);
        if (accepted != null && !accepted.fields().isEmpty()) {
            EnrichmentProposal.FieldCandidate field = accepted.fields().get(0).candidate();
            return (field.suggestedAction() == EnrichmentProposal.ReviewAction.CORROBORATE
                    ? field.field() + " already contains " + field.proposedValue()
                    : field.field() + " = " + field.proposedValue())
                    + sourceSuffix(field.source());
        }
        if (accepted != null && accepted.media() != null) {
            EnrichmentProposal.MediaCandidate media = accepted.media();
            return media.field() + ": image" + sourceSuffix(media.source());
        }
        if (!proposal.fields().isEmpty()) {
            EnrichmentProposal.FieldCandidate field = proposal.fields().get(0);
            return field.field() + " rejected: " + field.compatibilityError()
                    + sourceSuffix(field.source());
        }
        return "No value found";
    }

    private static String sourceSuffix(SourceRef source) {
        if (source == null || source.kind() == null || source.kind().isBlank()) return "";
        String property = source.propertyId() == null || source.propertyId().isBlank()
                ? "" : ", " + source.propertyId();
        return " [" + source.kind() + property + "]";
    }

    /** Every value that Apply will stage gets its own nested card and evidence list. */
    static List<Viewable> changeCards(EnrichmentProposal proposal) {
        List<Viewable> result = new ArrayList<>();
        String prefix = proposal.subject().type() + '-' + proposal.subject().targetId() + '-';
        int ordinal = 0;
        EnrichmentDecision accepted = EnrichmentDecision.acceptDefault(proposal);
        List<EnrichmentProposal.FieldCandidate> fields = accepted == null
                ? proposal.fields() : accepted.fields().stream()
                .map(EnrichmentDecision.FieldDecision::candidate).toList();
        for (EnrichmentProposal.FieldCandidate field : fields) {
            DynamicViewable change = changeCard(prefix + "field-" + ordinal++, field.field(),
                    field.proposedValue(), field.source(), field.suggestedAction().name(),
                    field.evidence());
            if (!field.compatible()) change.put("Compatibility", field.compatibilityError());
            result.add(change);
        }
        List<EnrichmentProposal.MediaCandidate> mediaCandidates = accepted == null
                ? proposal.media() : accepted.media() == null
                ? List.of() : List.of(accepted.media());
        for (EnrichmentProposal.MediaCandidate media : mediaCandidates) {
            result.add(changeCard(prefix + "media-" + ordinal++, media.field(), media.imageUrl(),
                    media.source(), "MEDIA", media.evidence()));
        }
        List<EnrichmentProposal.IdentityCandidate> identities = accepted == null
                ? proposal.identities() : accepted.identities();
        for (EnrichmentProposal.IdentityCandidate identity : identities) {
            if (identity.evidence().isEmpty()) continue;
            result.add(changeCard(prefix + "identity-" + ordinal++, "Identity",
                    identity.canonicalName(), identity.source(), "LINK",
                    identity.evidence()));
        }
        return List.copyOf(result);
    }

    private static DynamicViewable changeCard(
            String id, String field, Object value, SourceRef source, String action,
            List<datasource.evidence.ExtractedClaim> claims) {
        DynamicViewable card = new DynamicViewable(id, field);
        card.type("Proposed value");
        card.put("Target field", field);
        card.put("Value", value);
        card.put("Action", action);
        card.put("Source", source.kind() + (source.sourceId().isBlank()
                ? "" : " — " + source.sourceId()));
        if (!source.propertyId().isBlank()) card.put("Semantic property", source.propertyId());
        if (!source.recordUrl().isBlank()) card.put("Source URL", source.recordUrl());
        List<Viewable> evidence = evidenceCards(id, claims);
        if (!evidence.isEmpty()) card.put("Evidence", evidence);
        return card;
    }

    private static List<Viewable> evidenceCards(
            String parentId, List<datasource.evidence.ExtractedClaim> claims) {
        List<Viewable> cards = new ArrayList<>();
        int ordinal = 0;
        for (datasource.evidence.ExtractedClaim claim : claims) {
            for (datasource.evidence.EvidenceFragment fragment : claim.evidence()) {
                DynamicViewable evidence = new DynamicViewable(
                        parentId + "-evidence-" + ordinal++, fragment.section().isBlank()
                        ? fragment.document().title() : fragment.section());
                evidence.type("Evidence");
                evidence.put("Supporting text", fragment.excerpt());
                evidence.put("Document", fragment.document().title().isBlank()
                        ? fragment.document().documentId() : fragment.document().title());
                if (!fragment.document().url().isBlank()) {
                    evidence.put("Source URL", fragment.document().url());
                }
                evidence.put("Document version", fragment.document().versionId());
                evidence.put("Retrieved", fragment.document().retrievedAt());
                evidence.put("Extraction", claim.extractionMethod()
                        + " / " + claim.recipeVersion());
                evidence.put("Confidence", Math.round(claim.confidence() * 100) + "%");
                if (!claim.warnings().isEmpty()) {
                    evidence.put("Warnings", String.join("; ", claim.warnings()));
                }
                evidence.put("Claim ID", claim.claimId());
                cards.add(evidence);
            }
        }
        return List.copyOf(cards);
    }
}
