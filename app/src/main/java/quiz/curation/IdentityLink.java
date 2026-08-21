package quiz.curation;

import datasource.evidence.ExtractedClaim;

import java.util.List;

/** An approved link between a domain object and one record in an external source. */
public record IdentityLink(
        String type,
        String targetId,
        String sourceKind,
        String sourceId,
        String recordUrl,
        String canonicalName,
        String origin,
        List<ExtractedClaim> evidence) {
    public IdentityLink {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public IdentityLink(String type, String targetId, String sourceKind, String sourceId,
                        String recordUrl, String canonicalName, String origin) {
        this(type, targetId, sourceKind, sourceId, recordUrl, canonicalName, origin,
                List.of());
    }
}
