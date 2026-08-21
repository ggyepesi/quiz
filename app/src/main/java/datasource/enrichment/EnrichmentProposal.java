package datasource.enrichment;

import datasource.SourceRef;
import datasource.evidence.ExtractedClaim;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Provider-neutral, non-mutating candidates for one domain object. */
public record EnrichmentProposal(
        Subject subject,
        List<IdentityCandidate> identities,
        List<FieldCandidate> fields,
        List<MediaCandidate> media) {

    public EnrichmentProposal {
        subject = Objects.requireNonNull(subject, "Proposal subject is required");
        identities = copy(identities);
        fields = copy(fields);
        media = copy(media);
        Set<String> identityIds = identities.stream().map(IdentityCandidate::candidateId)
                .collect(Collectors.toSet());
        for (FieldCandidate field : fields) {
            validateIdentity(field.identityCandidateId(), identityIds, "field", field.field());
            validateClaims(subject, field.source(), field.evidence());
        }
        for (MediaCandidate candidate : media) {
            validateIdentity(candidate.identityCandidateId(), identityIds,
                    "media", candidate.field());
            validateClaims(subject, candidate.source(), candidate.evidence());
        }
        for (IdentityCandidate candidate : identities) {
            validateClaims(subject, candidate.source(), candidate.evidence());
        }
    }

    /**
     * Whether anything here would CHANGE this field, which is what decides that a gap is
     * filled and no further source need be asked. A corroboration answers a different
     * question — it says the value already present is supported — so counting it as
     * usable stopped the fallback sources that could have supplied the missing value.
     */
    public boolean hasUsableCandidate(String targetField) {
        return fields.stream().filter(FieldCandidate::compatible)
                .filter(c -> c.suggestedAction() != ReviewAction.IGNORE)
                .filter(c -> c.suggestedAction() != ReviewAction.CORROBORATE)
                .anyMatch(c -> sameField(targetField, c.field()))
                || media.stream().anyMatch(c -> sameField(targetField, c.field()));
    }

    /** Candidates that change nothing and exist to be read: evidence for a value the
     *  object already has. Counted apart from the rest so "12 candidates" cannot mean
     *  twelve things that would leave the data exactly as it was. */
    public long corroborationCount() {
        return fields.stream()
                .filter(c -> c.suggestedAction() == ReviewAction.CORROBORATE).count();
    }

    private static boolean sameField(String requested, String candidate) {
        return requested == null || requested.isBlank() || Objects.equals(requested, candidate);
    }

    public record Subject(String type, String targetId, String id, String displayName) {
        public Subject {
            type = required(type, "Subject type is required");
            targetId = text(targetId);
            id = text(id);
            displayName = text(displayName);
        }
        public Subject(String type, String id, String displayName) {
            this(type, id, id, displayName);
        }
    }

    public record IdentityCandidate(
            String candidateId,
            String canonicalName,
            List<String> aliases,
            String description,
            SourceRef source,
            double confidence,
            List<String> rationale,
            List<ExtractedClaim> evidence) {
        public IdentityCandidate {
            candidateId = required(candidateId, "Identity candidate identifier is required");
            canonicalName = text(canonicalName);
            aliases = strings(aliases);
            description = text(description);
            source = Objects.requireNonNull(source, "Identity candidate source is required");
            if (Double.isNaN(confidence) || confidence < 0 || confidence > 1) {
                throw new IllegalArgumentException("Identity confidence must be between 0 and 1");
            }
            rationale = strings(rationale);
            evidence = copy(evidence);
        }
        /** Migration bridge for existing Wikimedia, DBpedia, and source-page providers.
         * Remove when those providers emit {@link ExtractedClaim} evidence. */
        @Deprecated(forRemoval = true)
        public IdentityCandidate(String candidateId, String canonicalName,
                                 List<String> aliases, String description,
                                 SourceRef source, double confidence,
                                 List<String> rationale) {
            this(candidateId, canonicalName, aliases, description, source, confidence,
                    rationale, List.of());
        }
    }

    public record FieldCandidate(
            String candidateId,
            String identityCandidateId,
            String field,
            Object currentValue,
            Object proposedValue,
            SourceRef source,
            ReviewAction suggestedAction,
            String compatibilityError,
            boolean targetCollection,
            List<ExtractedClaim> evidence) {
        public FieldCandidate {
            candidateId = required(candidateId, "Field candidate identifier is required");
            identityCandidateId = text(identityCandidateId);
            field = required(field, "Target field is required");
            source = Objects.requireNonNull(source, "Field candidate source is required");
            suggestedAction = suggestedAction == null ? ReviewAction.FILL_IF_EMPTY
                    : suggestedAction;
            compatibilityError = text(compatibilityError);
            evidence = copy(evidence);
            for (ExtractedClaim claim : evidence) {
                if (!source.propertyId().isBlank()
                        && !source.propertyId().equals(claim.semanticProperty())) {
                    throw new IllegalArgumentException("Evidence semantic property "
                            + claim.semanticProperty() + " does not match candidate property "
                            + source.propertyId());
                }
                if (claim.proposedValue() != null
                        && !sameProposedValue(claim.proposedValue(), proposedValue)) {
                    throw new IllegalArgumentException("Evidence value "
                            + claim.proposedValue() + " does not match proposed value "
                            + proposedValue);
                }
            }
        }
        /** Migration bridge for existing providers which do not yet emit evidence. */
        @Deprecated(forRemoval = true)
        public FieldCandidate(String candidateId, String identityCandidateId, String field,
                              Object currentValue, Object proposedValue, SourceRef source,
                              ReviewAction suggestedAction) {
            this(candidateId, identityCandidateId, field, currentValue, proposedValue,
                    source, suggestedAction, null,
                    currentValue instanceof java.util.Collection<?>, List.of());
        }
        /** Migration bridge for existing providers which do not yet emit evidence. */
        @Deprecated(forRemoval = true)
        public FieldCandidate(String candidateId, String identityCandidateId, String field,
                              Object currentValue, Object proposedValue, SourceRef source,
                              ReviewAction suggestedAction, String compatibilityError) {
            this(candidateId, identityCandidateId, field, currentValue, proposedValue,
                    source, suggestedAction, compatibilityError,
                    currentValue instanceof java.util.Collection<?>, List.of());
        }
        /** Migration bridge for existing providers which do not yet emit evidence. */
        @Deprecated(forRemoval = true)
        public FieldCandidate(String candidateId, String identityCandidateId, String field,
                              Object currentValue, Object proposedValue, SourceRef source,
                              ReviewAction suggestedAction, String compatibilityError,
                              boolean targetCollection) {
            this(candidateId, identityCandidateId, field, currentValue, proposedValue,
                    source, suggestedAction, compatibilityError, targetCollection, List.of());
        }
        public boolean compatible() { return compatibilityError.isBlank(); }
    }

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
            boolean collection,
            List<ExtractedClaim> evidence) {
        public MediaCandidate {
            candidateId = required(candidateId, "Media candidate identifier is required");
            identityCandidateId = text(identityCandidateId);
            field = required(field, "Media target field is required");
            imageUrl = required(imageUrl, "Media URL is required");
            previewUrl = text(previewUrl);
            source = Objects.requireNonNull(source, "Media source is required");
            discoveryMethod = text(discoveryMethod);
            if (Double.isNaN(confidence) || confidence < 0 || confidence > 1) {
                throw new IllegalArgumentException("Media confidence must be between 0 and 1");
            }
            attribution = text(attribution);
            license = text(license);
            evidence = copy(evidence);
            for (ExtractedClaim claim : evidence) {
                if (claim.proposedValue() != null
                        && !Objects.equals(String.valueOf(claim.proposedValue()), imageUrl)) {
                    throw new IllegalArgumentException("Evidence media value "
                            + claim.proposedValue() + " does not match image URL " + imageUrl);
                }
            }
        }
        /** Migration bridge for existing Wikimedia, DBpedia, and source-page providers.
         * Remove when those providers emit {@link ExtractedClaim} evidence. */
        @Deprecated(forRemoval = true)
        public MediaCandidate(String candidateId, String identityCandidateId, String field,
                              String imageUrl, String previewUrl, SourceRef source,
                              String discoveryMethod, double confidence, String attribution,
                              String license, boolean collection) {
            this(candidateId, identityCandidateId, field, imageUrl, previewUrl, source,
                    discoveryMethod, confidence, attribution, license, collection, List.of());
        }
    }

    /*
     * The shorter constructors below default `evidence` to empty. They exist for the
     * providers that do not yet attach evidence — DBpedia field and image, Wikimedia
     * field and image, and the source-page image provider — and each one that starts
     * emitting claims should lose its use of them. When the last goes, so do they;
     * until then the count is the size of the gap, and it is meant to shrink.
     */

    public enum ReviewAction {
        REPLACE, FILL_IF_EMPTY, ADD_TO_COLLECTION,
        /** Persist reviewed evidence for the current value without changing that value. */
        CORROBORATE,
        IGNORE
    }

    private static void validateIdentity(String id, Set<String> identities,
                                         String kind, String name) {
        if (!text(id).isBlank() && !identities.contains(id)) {
            throw new IllegalArgumentException("Unknown identity candidate " + id
                    + " referenced by " + kind + " " + name);
        }
    }

    private static void validateClaims(Subject subject, SourceRef source,
                                       List<ExtractedClaim> claims) {
        for (ExtractedClaim claim : claims) {
            if (!claim.subject().id().equals(subject.targetId())
                    && !claim.subject().id().equals(subject.id())) {
                throw new IllegalArgumentException("Evidence subject " + claim.subject().id()
                        + " does not match proposal subject " + subject.targetId());
            }
            for (var fragment : claim.evidence()) {
                if (!source.kind().equalsIgnoreCase(fragment.document().kind())) {
                    throw new IllegalArgumentException("Evidence source "
                            + fragment.document().kind() + " does not match candidate source "
                            + source.kind());
                }
                if (!source.sourceId().isBlank()
                        && !fragment.document().sourceId().isBlank()
                        && !source.sourceId().equals(fragment.document().sourceId())) {
                    throw new IllegalArgumentException("Evidence document "
                            + fragment.document().sourceId()
                            + " does not match source record " + source.sourceId());
                }
            }
        }
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }
    private static boolean sameProposedValue(Object evidence, Object candidate) {
        Object value = candidate;
        if (candidate instanceof java.util.Collection<?> values && values.size() == 1) {
            value = values.iterator().next();
        }
        return Objects.equals(evidence, value)
                || ExtractedClaim.sameStableValue(evidence, value);
    }
    private static List<String> strings(List<String> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull)
                .map(String::trim).filter(s -> !s.isBlank()).toList();
    }
    private static String required(String value, String message) {
        String normalized = text(value);
        if (normalized.isBlank()) throw new IllegalArgumentException(message);
        return normalized;
    }
    private static String text(String value) { return value == null ? "" : value.trim(); }
}
