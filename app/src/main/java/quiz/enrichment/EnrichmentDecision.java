package quiz.enrichment;

import java.util.Comparator;
import java.util.List;

/** The explicit user decision produced by the enrichment review component. */
public record EnrichmentDecision(
        EnrichmentProposal.Subject subject,
        EnrichmentProposal.IdentityCandidate identity,
        List<FieldDecision> fields,
        EnrichmentProposal.MediaCandidate media) {

    public EnrichmentDecision {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public record FieldDecision(
            EnrichmentProposal.FieldCandidate candidate,
            EnrichmentProposal.ReviewAction action) { }

    /**
     * The "apply as proposed" decision for a proposal: every field candidate with its
     * suggested action, plus the highest-confidence media candidate. The identity is the
     * one the applied item is attributed to (so the correction's origin/identity-link is
     * right). Returns {@code null} when there is nothing applicable — used by the batch
     * review to offer each member a default accept without the full single-member UI.
     */
    public static EnrichmentDecision acceptDefault(EnrichmentProposal proposal) {
        if (proposal == null || proposal.identities().isEmpty()) {
            return null;
        }
        List<FieldDecision> fields = proposal.fields().stream()
                .filter(EnrichmentProposal.FieldCandidate::compatible)
                .filter(candidate ->
                        candidate.suggestedAction() != EnrichmentProposal.ReviewAction.IGNORE)
                .map(candidate -> new FieldDecision(candidate, candidate.suggestedAction()))
                .toList();
        EnrichmentProposal.MediaCandidate media = proposal.media().stream()
                .max(Comparator.comparingDouble(EnrichmentProposal.MediaCandidate::confidence))
                .orElse(null);
        if (fields.isEmpty() && media == null) {
            return null;
        }
        String identityId = media != null
                ? media.identityCandidateId()
                : fields.get(0).candidate().identityCandidateId();
        EnrichmentProposal.IdentityCandidate identity = proposal.identities().stream()
                .filter(candidate -> candidate.candidateId().equals(identityId))
                .findFirst()
                .orElse(proposal.identities().get(0));
        return new EnrichmentDecision(proposal.subject(), identity, fields, media);
    }
}
