package quiz.enrichment;

import java.util.List;

/**
 * Provider-neutral enrichment candidates for one domain object. Discovery only
 * describes possibilities; it never mutates the object or its curation sidecar.
 */
public record EnrichmentProposal(
        Subject subject,
        List<IdentityCandidate> identities,
        List<FieldCandidate> fields,
        List<MediaCandidate> media) {

    public EnrichmentProposal {
        identities = identities == null ? List.of() : List.copyOf(identities);
        fields = fields == null ? List.of() : List.copyOf(fields);
        media = media == null ? List.of() : List.copyOf(media);
    }

    /**
     * The domain object and the external lookup identity are deliberately separate:
     * {@code targetId} is where accepted corrections are written; {@code id} is the
     * provider identifier (usually a resolved Wikidata QID).
     */
    public record Subject(String type, String targetId, String id, String displayName) {
        /** Backwards-compatible form for sources whose domain ID is also their lookup ID. */
        public Subject(String type, String id, String displayName) {
            this(type, id, id, displayName);
        }
    }

    /** A record in one external source which may represent {@link #subject}. */
    public record IdentityCandidate(
            String candidateId,
            String canonicalName,
            List<String> aliases,
            String description,
            SourceRef source,
            double confidence,
            List<String> evidence) {

        public IdentityCandidate {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    /** A generic source record, independent of Wikidata, DBpedia, or a domain site. */
    public record SourceRef(
            String kind, String sourceId, String recordUrl, String propertyId) {
        public SourceRef(String kind, String sourceId, String recordUrl) {
            this(kind, sourceId, recordUrl, null);
        }
    }

    /** A proposed non-media value. The action is chosen explicitly in the review UI. */
    public record FieldCandidate(
            String candidateId,
            String identityCandidateId,
            String field,
            Object currentValue,
            Object proposedValue,
            SourceRef source,
            ReviewAction suggestedAction,
            String compatibilityError,
            boolean targetCollection) {
        public FieldCandidate(String candidateId, String identityCandidateId,
                              String field, Object currentValue, Object proposedValue,
                              SourceRef source, ReviewAction suggestedAction) {
            this(candidateId, identityCandidateId, field, currentValue, proposedValue,
                    source, suggestedAction, null,
                    currentValue instanceof java.util.Collection<?>);
        }

        public FieldCandidate(String candidateId, String identityCandidateId,
                              String field, Object currentValue, Object proposedValue,
                              SourceRef source, ReviewAction suggestedAction,
                              String compatibilityError) {
            this(candidateId, identityCandidateId, field, currentValue, proposedValue,
                    source, suggestedAction, compatibilityError,
                    currentValue instanceof java.util.Collection<?>);
        }

        public boolean compatible() {
            return compatibilityError == null || compatibilityError.isBlank();
        }
    }

    /** An attributed media candidate associated with a resolved identity candidate. */
    public record MediaCandidate(
            String candidateId,
            String identityCandidateId,
            String field,
            String imageUrl,
            String previewUrl,
            SourceRef source,
            String discoveryMethod,
            double confidence,
            String attribution,
            String license,
            boolean collection) { }

    public enum ReviewAction {
        REPLACE,
        FILL_IF_EMPTY,
        ADD_TO_COLLECTION,
        IGNORE
    }
}
