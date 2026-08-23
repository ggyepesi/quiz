package quiz.enrichment;

import datasource.enrichment.EnrichmentProposal;

/** Discovery and the optional accepted review decision are preserved together. */
public record FindDataResult(
        EnrichmentProposal proposal,
        EnrichmentDecision acceptedDecision,
        java.util.List<datasource.enrichment.SourceYield> sourceYields) {

    public FindDataResult {
        sourceYields = sourceYields == null ? java.util.List.of()
                : java.util.List.copyOf(sourceYields);
    }
}
