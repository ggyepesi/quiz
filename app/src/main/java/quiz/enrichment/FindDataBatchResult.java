package quiz.enrichment;

import java.util.List;

/** All terminal member results from a batch, including accepted partial work. */
public record FindDataBatchResult(
        List<FindDataResult> results,
        int skipped,
        List<datasource.enrichment.SourceYield> sourceYields) {

    public FindDataBatchResult {
        results = results == null ? List.of() : List.copyOf(results);
        sourceYields = datasource.enrichment.SourceYield.aggregate(sourceYields);
    }

    // Deliberately no constructor deriving the yields from `results`: a member whose
    // providers all failed contributes no FindDataResult at all, so that derivation
    // drops exactly the failures the measurement exists to record.

    public List<EnrichmentDecision> acceptedDecisions() {
        return results.stream()
                .map(FindDataResult::acceptedDecision)
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
