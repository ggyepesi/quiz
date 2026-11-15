package datasource.api.discovery;

import java.util.List;

/** Exact bounded seeds for a source-native related-record inspection. */
public record RelatedRecordDiscoveryRequest(List<RelatedRecordSeed> seeds) {
    public RelatedRecordDiscoveryRequest {
        seeds = seeds == null ? List.of() : seeds.stream()
                .filter(java.util.Objects::nonNull).distinct().toList();
    }
}
