package quiz.enrichment;

/** Discovery and the optional accepted review decision are preserved together. */
public record FindDataResult(
        EnrichmentProposal proposal,
        EnrichmentDecision acceptedDecision) {
}
