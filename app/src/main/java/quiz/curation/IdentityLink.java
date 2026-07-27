package quiz.curation;

/** An approved link between a domain object and one record in an external source. */
public record IdentityLink(
        String type,
        String targetId,
        String sourceKind,
        String sourceId,
        String recordUrl,
        String canonicalName,
        String origin) { }
