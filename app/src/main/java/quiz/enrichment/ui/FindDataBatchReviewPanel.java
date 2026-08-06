package quiz.enrichment.ui;

import quiz.enrichment.BatchReviewDecision;
import quiz.enrichment.EnrichmentDecision;
import quiz.enrichment.EnrichmentProposal;

import javax.swing.JCheckBox;
import javax.swing.JDialog;

import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One approve/skip step over every member's proposal, as a {@link CategorizedReviewPanel}:
 * a checkable row per member showing the value (or image) that would be applied, split into
 * FOUND (compatible, accepted by default) and NOT FOUND (no value found or rejected by field
 * type — kept visible so the members that need manual curation don't vanish, the same way
 * identity resolution shows its "No match" set). Fine-grained per-field editing stays in the
 * single-member {@link EnrichmentReviewPanel}; this is the bulk accept for a batch fill.
 */
public final class FindDataBatchReviewPanel {

    private FindDataBatchReviewPanel() { }

    public static void showDialog(
            Component owner, String title, String prompt,
            List<EnrichmentProposal> proposals, Consumer<BatchReviewDecision> onDone) {
        CategorizedReviewPanel.showDialog(owner, title, prompt,
                                          sections(proposals), new Dimension(720, 560), accepted -> onDone.accept(decision(accepted)));
    }

    public static JDialog showModeless(
            Component owner, String title, String prompt,
            List<EnrichmentProposal> proposals, Consumer<BatchReviewDecision> onDone) {
        return CategorizedReviewPanel.showModeless(owner, title, prompt,
                                                   sections(proposals), new Dimension(720, 560),
                                                   accepted -> onDone.accept(decision(accepted)));
    }

    private static List<CategorizedReviewPanel.Section<EnrichmentProposal>> sections(
            List<EnrichmentProposal> proposals) {
        List<CategorizedReviewPanel.Row<EnrichmentProposal>> found = new ArrayList<>();
        List<CategorizedReviewPanel.Row<EnrichmentProposal>> notFound = new ArrayList<>();
        for (EnrichmentProposal proposal : proposals == null
                ? List.<EnrichmentProposal>of() : proposals) {
            if (EnrichmentDecision.acceptDefault(proposal) != null) {
                found.add(row(proposal, true));
            } else {
                notFound.add(row(proposal, false));
            }
        }
        return List.of(
                new CategorizedReviewPanel.Section<>("Found",
                                                     "Found values — accepted; uncheck any you don't want", found),
                new CategorizedReviewPanel.Section<>("Not found",
                                                     "No value found (or rejected by field type) — curate manually", notFound));
    }

    private static CategorizedReviewPanel.Row<EnrichmentProposal> row(
            EnrichmentProposal proposal, boolean selectable) {
        String label = rowLabel(proposal);
        // A REPLACE would overwrite an existing (possibly curated) value, so it is NOT
        // pre-checked: overwriting is always a deliberate tick.
        boolean overwrite = overwrites(proposal);
        JCheckBox accept = new JCheckBox(
                CategorizedReviewPanel.truncate(label, 90)
                        + (overwrite ? "  [overwrites existing]" : ""),
                selectable && !overwrite);
        accept.setEnabled(selectable);
        accept.setToolTipText(overwrite
                                      ? label + " — would replace the current value; check to apply"
                                      : label);
        return new CategorizedReviewPanel.Row<>(proposal, accept);
    }

    private static BatchReviewDecision decision(List<EnrichmentProposal> accepted) {
        List<EnrichmentDecision> decisions = new ArrayList<>();
        for (EnrichmentProposal proposal : accepted) {
            EnrichmentDecision decision = EnrichmentDecision.acceptDefault(proposal);
            if (decision != null) {
                decisions.add(decision);
            }
        }
        return new BatchReviewDecision(decisions);
    }

    /** A proposal overwrites existing data when any of its field candidates is a REPLACE. */
    private static boolean overwrites(EnrichmentProposal proposal) {
        return proposal.fields().stream().anyMatch(
                f -> f.suggestedAction() == EnrichmentProposal.ReviewAction.REPLACE);
    }

    private static String rowLabel(EnrichmentProposal proposal) {
        String detail;
        EnrichmentDecision accepted = EnrichmentDecision.acceptDefault(proposal);
        if (accepted != null && !accepted.fields().isEmpty()) {
            // A routed proposal may retain an incompatible primary candidate before a
            // usable fallback candidate. Show the candidate that will actually be applied,
            // together with its provenance, rather than misleadingly showing the rejected
            // primary value in the Found tab.
            EnrichmentProposal.FieldCandidate field =
                    accepted.fields().get(0).candidate();
            detail = field.field() + " = " + field.proposedValue()
                    + sourceSuffix(field.source());
        } else if (!proposal.media().isEmpty()) {
            EnrichmentProposal.MediaCandidate media = proposal.media().get(0);
            detail = media.field() + ": image" + sourceSuffix(media.source());
        } else if (!proposal.fields().isEmpty()) {
            EnrichmentProposal.FieldCandidate field = proposal.fields().get(0);
            detail = field.field() + " rejected: " + field.compatibilityError()
                    + sourceSuffix(field.source());
        } else {
            detail = "(no value)";
        }
        return proposal.subject().displayName() + "  —  " + detail;
    }

    private static String sourceSuffix(EnrichmentProposal.SourceRef source) {
        if (source == null || source.kind() == null || source.kind().isBlank()) {
            return "";
        }
        String property = source.propertyId() == null || source.propertyId().isBlank()
                ? "" : ", " + source.propertyId();
        return "  [" + source.kind() + property + "]";
    }
}
