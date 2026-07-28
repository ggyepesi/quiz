package quiz.enrichment;

import java.util.List;

/** All terminal member results from a batch, including accepted partial work. */
public record FindDataBatchResult(
        List<FindDataResult> results,
        int skipped) {

    public FindDataBatchResult {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public List<EnrichmentDecision> acceptedDecisions() {
        return results.stream()
                .map(FindDataResult::acceptedDecision)
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
