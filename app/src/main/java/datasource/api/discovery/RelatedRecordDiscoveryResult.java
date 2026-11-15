package datasource.api.discovery;

import java.util.List;

/** Complete or partial outcome of one bounded related-record inspection. */
public record RelatedRecordDiscoveryResult(
        List<DiscoveredRelatedRecord> records,
        int seedCount,
        int searchedCandidates,
        List<String> failedWork) {
    public RelatedRecordDiscoveryResult {
        records = records == null ? List.of() : List.copyOf(records);
        seedCount = Math.max(0, seedCount);
        searchedCandidates = Math.max(0, searchedCandidates);
        failedWork = failedWork == null ? List.of() : List.copyOf(failedWork);
    }

    public boolean partial() { return !failedWork.isEmpty(); }
}
