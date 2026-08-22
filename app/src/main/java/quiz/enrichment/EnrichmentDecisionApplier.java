package quiz.enrichment;

import datasource.enrichment.EnrichmentProposal;

import datasource.SourceRef;

import quiz.curation.Correction;
import quiz.curation.Corrections;
import quiz.curation.CurationStaging;
import quiz.curation.IdentityLink;
import quiz.curation.ManualCuration;
import domain.DomainModel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import domain.DomainSchemas;

/**
 * Persists an approved enrichment decision as identity metadata plus ordinary
 * correction overlays. Saving happens before the live domain is changed.
 */
public final class EnrichmentDecisionApplier {

    private EnrichmentDecisionApplier() { }

    /** Put a reviewed decision into the shared curation transaction without crossing the
     * durable save boundary. The caller may preview the returned corrections immediately;
     * {@code Save staged changes} remains the one explicit commit action. */
    public static int stage(
            DomainModel domain, CurationStaging staging, EnrichmentDecision decision) {
        if (domain == null || staging == null || decision == null) return 0;
        Prepared prepared = prepare(domain, decision);
        prepared.identities().forEach(staging::stage);
        prepared.corrections().forEach(staging::stage);
        return prepared.corrections().size() + prepared.identities().size();
    }

    public static int apply(
            DomainModel domain,
            ManualCuration curation,
            EnrichmentDecision decision) throws IOException {
        if (domain == null || curation == null || decision == null) {
            return 0;
        }

        String type = decision.subject().type();
        String targetId = decision.subject().targetId();
        Prepared prepared = prepare(domain, decision);
        Set<String> sourceKinds = new LinkedHashSet<>();
        decision.identities().stream().map(i -> i.source().kind()).forEach(sourceKinds::add);

        List<Correction> previousCorrections = curation.corrections().stream()
                .filter(c -> (c.type() == null || type.equals(c.type()))
                        && targetId.equals(c.qid())
                        && affectedFields(decision).contains(c.field()))
                .toList();
        List<IdentityLink> previousLinks = curation.identityLinks().stream()
                .filter(link -> type.equals(link.type())
                        && targetId.equals(link.targetId())
                        && sourceKinds.contains(link.sourceKind()))
                .toList();

        try {
            prepared.identities().forEach(curation::putIdentityLink);
            for (Correction correction : prepared.corrections()) {
                curation.put(correction.type(), correction.qid(), correction.field(),
                        correction.value(), correction.origin(), correction.valueKind(),
                        correction.policy(), correction.source());
            }
            curation.save();
        } catch (IOException | RuntimeException ex) {
            for (String field : affectedFields(decision)) {
                curation.remove(type, targetId, field);
            }
            previousCorrections.forEach(curation::restore);
            sourceKinds.forEach(kind -> curation.removeIdentityLink(type, targetId, kind));
            previousLinks.forEach(curation::putIdentityLink);
            throw ex;
        }

        return Corrections.apply(domain.instances(), List.of(curation));
    }

    private static Prepared prepare(DomainModel domain, EnrichmentDecision decision) {
        String type = decision.subject().type();
        String targetId = decision.subject().targetId();
        List<IdentityLink> identities = decision.identities().stream().map(identity -> {
            String sourceKind = identity.source().kind();
            return new IdentityLink(type, targetId, sourceKind, identity.source().sourceId(),
                    identity.source().recordUrl(), identity.canonicalName(), origin(sourceKind),
                    identity.evidence());
        }).toList();
        List<Correction> corrections = new ArrayList<>();
        for (EnrichmentDecision.FieldDecision fieldDecision : decision.fields()) {
            EnrichmentProposal.FieldCandidate candidate = fieldDecision.candidate();
            if (!candidate.compatible()) throw new IllegalArgumentException("Cannot apply "
                    + candidate.field() + ": " + candidate.compatibilityError());
            objectview.field.FieldRef schema = DomainSchemas.resolve(
                    domain, type, candidate.field());
            if (fieldDecision.action() == EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION
                    && (schema == null || !schema.collection())) {
                throw new IllegalArgumentException("Cannot add to " + candidate.field()
                        + ": the target field is not a collection.");
            }
            String problem = FieldValueCompatibility.problem(schema, candidate.proposedValue());
            if (problem != null) throw new IllegalArgumentException("Cannot apply "
                    + candidate.field() + ": " + problem);
            Object value = approvedValue(candidate, fieldDecision.action());
            validateEvidenceEntity(candidate, value);
            SourceRef candidateSource = candidate.source();
            quiz.curation.ValueSource source = valueSource(candidateSource, candidate.evidence());
            Object stored = referenceId(value) == null ? value : referenceId(value);
            String valueKind = referenceId(value) == null ? null : candidate.targetCollection()
                    ? Correction.REFERENCE_COLLECTION : Correction.REFERENCE;
            corrections.add(new Correction(type, targetId, candidate.field(), stored,
                    origin(candidateSource.kind()), valueKind, policy(fieldDecision.action()),
                    source));
        }
        EnrichmentProposal.MediaCandidate media = decision.media();
        if (media != null) {
            corrections.add(new Correction(type, targetId, media.field(), media.imageUrl(),
                    origin(media.source().kind()), media.collection()
                    ? Correction.MEDIA_COLLECTION : Correction.MEDIA,
                    quiz.curation.CorrectionPolicy.FILL_IF_EMPTY,
                    valueSource(media.source(), media.evidence())));
        }
        return new Prepared(List.copyOf(corrections), identities);
    }

    private static quiz.curation.ValueSource valueSource(
            SourceRef source, List<datasource.evidence.ExtractedClaim> evidence) {
        return source == null ? null : new quiz.curation.ValueSource(source.kind(),
                source.sourceId(), source.propertyId(), source.propertyLabel(),
                source.direction(), source.recordUrl(), evidence);
    }

    private record Prepared(List<Correction> corrections, List<IdentityLink> identities) { }

    /** The identifier an approved reference value stands for, or null when it is not a
     *  reference. A single-valued field yields its target's id; a collection yields the
     *  one target it is adding. */
    private static Object referenceId(Object value) {
        if (value instanceof objectview.Viewable target) {
            String id = target.getIdentifier();
            return id == null || id.isBlank() ? null : id;
        }
        if (value instanceof java.util.Collection<?> values && values.size() == 1) {
            return referenceId(values.iterator().next());
        }
        return null;
    }

    private static void validateEvidenceEntity(
            EnrichmentProposal.FieldCandidate candidate, Object value) {
        String actual = (String) referenceId(value);
        for (datasource.evidence.ExtractedClaim claim : candidate.evidence()) {
            if (claim.proposedEntity() != null
                    && !claim.proposedEntity().id().equals(actual)) {
                throw new IllegalArgumentException("Evidence entity "
                        + claim.proposedEntity().qualifiedId()
                        + " does not match proposed reference " + actual);
            }
        }
    }

    private static List<String> affectedFields(EnrichmentDecision decision) {
        List<String> fields = new ArrayList<>();
        for (EnrichmentDecision.FieldDecision field : decision.fields()) {
            fields.add(field.candidate().field());
        }
        if (decision.media() != null) {
            fields.add(decision.media().field());
        }
        return fields.stream().distinct().toList();
    }

    private static Object approvedValue(
            EnrichmentProposal.FieldCandidate candidate,
            EnrichmentProposal.ReviewAction action) {
        // Persist only the reviewed contribution. ADD_* policy performs the union when
        // replayed, so regenerated base values remain intact and are not frozen into the
        // curation sidecar.
        return candidate.proposedValue();
    }

    private static String origin(String sourceKind) {
        if (sourceKind == null || sourceKind.isBlank()) {
            return "enrichment";
        }
        return sourceKind.toLowerCase(Locale.ROOT)
                .replaceFirst("^https?://", "")
                .replaceFirst("^www\\.", "")
                .replaceAll("[^a-z0-9.-]+", "-");
    }

    private static quiz.curation.CorrectionPolicy policy(
            EnrichmentProposal.ReviewAction action) {
        if (action == null) return quiz.curation.CorrectionPolicy.FILL_IF_EMPTY;
        return switch (action) {
            case REPLACE -> quiz.curation.CorrectionPolicy.REPLACE;
            case ADD_TO_COLLECTION -> quiz.curation.CorrectionPolicy.ADD_TO_COLLECTION;
            case CORROBORATE -> quiz.curation.CorrectionPolicy.EVIDENCE_ONLY;
            case FILL_IF_EMPTY, IGNORE -> quiz.curation.CorrectionPolicy.FILL_IF_EMPTY;
        };
    }
}
