package quiz.enrichment;

import datasource.enrichment.EnrichmentProposal;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Locale;

/** The explicit user decision produced by the enrichment review component. */
public record EnrichmentDecision(
        EnrichmentProposal.Subject subject,
        List<EnrichmentProposal.IdentityCandidate> identities,
        List<FieldDecision> fields,
        EnrichmentProposal.MediaCandidate media) {

    public EnrichmentDecision {
        subject = Objects.requireNonNull(subject, "Decision subject is required");
        identities = identities == null ? List.of() : List.copyOf(identities);
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    /** Compatibility form for callers which approve one source identity. */
    public EnrichmentDecision(EnrichmentProposal.Subject subject,
                              EnrichmentProposal.IdentityCandidate identity,
                              List<FieldDecision> fields,
                              EnrichmentProposal.MediaCandidate media) {
        this(subject, identity == null ? List.of() : List.of(identity), fields, media);
    }

    /** Compatibility accessor; new code should use {@link #identities()}. */
    public EnrichmentProposal.IdentityCandidate identity() {
        return identities.isEmpty() ? null : identities.get(0);
    }

    public record FieldDecision(
            EnrichmentProposal.FieldCandidate candidate,
            EnrichmentProposal.ReviewAction action) { }

    /**
     * The "apply as proposed" decision for a proposal: every field candidate with its
     * suggested action, plus the highest-confidence media candidate. The identity is the
     * one the applied item is attributed to (so the correction's origin/identity-link is
     * right). Returns {@code null} when there is nothing applicable — used by the batch
     * review to offer each member a default accept without the full single-member UI.
     */
    public static EnrichmentDecision acceptDefault(EnrichmentProposal proposal) {
        if (proposal == null) {
            return null;
        }
        List<FieldDecision> fields = proposal.fields().stream()
                .filter(EnrichmentProposal.FieldCandidate::compatible)
                .filter(candidate ->
                        candidate.suggestedAction() != EnrichmentProposal.ReviewAction.IGNORE)
                .map(candidate -> new FieldDecision(candidate, candidate.suggestedAction()))
                .toList();
        EnrichmentProposal.MediaCandidate media = proposal.media().stream()
                .max(Comparator.comparingDouble(EnrichmentProposal.MediaCandidate::confidence))
                .orElse(null);
        if (fields.isEmpty() && media == null) {
            return null;
        }
        Set<String> identityIds = new LinkedHashSet<>();
        fields.stream().map(FieldDecision::candidate)
                .map(EnrichmentProposal.FieldCandidate::identityCandidateId)
                .filter(id -> id != null && !id.isBlank()).forEach(identityIds::add);
        if (media != null && media.identityCandidateId() != null
                && !media.identityCandidateId().isBlank()) {
            identityIds.add(media.identityCandidateId());
        }
        List<EnrichmentProposal.IdentityCandidate> identities = proposal.identities().stream()
                .filter(candidate -> identityIds.contains(candidate.candidateId())).toList();
        if (hasAmbiguousIdentitySources(identities)) {
            return null; // choosing between two records from one source needs explicit review
        }
        return new EnrichmentDecision(proposal.subject(), identities, fields, media);
    }

    /** True when applying automatically would silently replace one identity link with
     * another because the curation sidecar stores one link per source kind. */
    public static boolean requiresIdentityChoice(EnrichmentProposal proposal) {
        if (proposal == null) return false;
        Set<String> used = new LinkedHashSet<>();
        proposal.fields().stream().filter(EnrichmentProposal.FieldCandidate::compatible)
                .filter(c -> c.suggestedAction() != EnrichmentProposal.ReviewAction.IGNORE)
                .map(EnrichmentProposal.FieldCandidate::identityCandidateId)
                .filter(id -> id != null && !id.isBlank()).forEach(used::add);
        proposal.media().stream()
                .max(Comparator.comparingDouble(EnrichmentProposal.MediaCandidate::confidence))
                .map(EnrichmentProposal.MediaCandidate::identityCandidateId)
                .filter(id -> id != null && !id.isBlank()).ifPresent(used::add);
        return hasAmbiguousIdentitySources(proposal.identities().stream()
                .filter(i -> used.contains(i.candidateId())).toList());
    }

    private static boolean hasAmbiguousIdentitySources(
            List<EnrichmentProposal.IdentityCandidate> identities) {
        Set<String> kinds = new LinkedHashSet<>();
        for (EnrichmentProposal.IdentityCandidate identity : identities) {
            String kind = identity.source().kind().toLowerCase(Locale.ROOT);
            if (!kinds.add(kind)) return true;
        }
        return false;
    }
}
