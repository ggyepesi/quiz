package quiz.enrichment;

import datasource.enrichment.EnrichmentProposal;

import process.ProcessInputRequest;

/** Typed UI pause emitted by Find Data after discovery, not by its Swing caller. */
public record EnrichmentReviewRequest(
        String title,
        String prompt,
        EnrichmentProposal proposal)
        implements ProcessInputRequest<EnrichmentDecision> {

    @Override public Class<EnrichmentDecision> responseType() {
        return EnrichmentDecision.class;
    }
}
