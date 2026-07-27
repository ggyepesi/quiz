package quiz.enrichment;

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
}
