package quiz.enrichment;

import quiz.curation.Correction;
import quiz.curation.Corrections;
import quiz.curation.IdentityLink;
import quiz.curation.ManualCuration;
import quiz.transform.ui.DomainModel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Persists an approved enrichment decision as identity metadata plus ordinary
 * correction overlays. Saving happens before the live domain is changed.
 */
public final class EnrichmentDecisionApplier {

    private EnrichmentDecisionApplier() { }

    public static int apply(
            DomainModel domain,
            ManualCuration curation,
            EnrichmentDecision decision) throws IOException {
        if (domain == null || curation == null || decision == null
                || decision.identity() == null) {
            return 0;
        }

        String type = decision.subject().type();
        String targetId = decision.subject().targetId();
        String sourceKind = decision.identity().source().kind();
        String origin = origin(sourceKind);

        List<Correction> previousCorrections = curation.corrections().stream()
                .filter(c -> (c.type() == null || type.equals(c.type()))
                        && targetId.equals(c.qid())
                        && affectedFields(decision).contains(c.field()))
                .toList();
        List<IdentityLink> previousLinks = curation.identityLinks().stream()
                .filter(link -> type.equals(link.type())
                        && targetId.equals(link.targetId())
                        && java.util.Objects.equals(sourceKind, link.sourceKind()))
                .toList();

        try {
            EnrichmentProposal.IdentityCandidate identity = decision.identity();
            curation.putIdentityLink(new IdentityLink(
                    type, targetId, sourceKind, identity.source().sourceId(),
                    identity.source().recordUrl(), identity.canonicalName(), origin));

            for (EnrichmentDecision.FieldDecision fieldDecision : decision.fields()) {
                EnrichmentProposal.FieldCandidate candidate = fieldDecision.candidate();
                if (!candidate.compatible()) {
                    throw new IllegalArgumentException("Cannot apply " + candidate.field()
                            + ": " + candidate.compatibilityError());
                }
                objectview.field.FieldRef targetSchema =
                        quiz.transform.ui.DomainSchemas.resolve(
                                domain, type, candidate.field());
                if (fieldDecision.action()
                        == EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION
                        && (targetSchema == null || !targetSchema.collection())) {
                    throw new IllegalArgumentException("Cannot add to " + candidate.field()
                            + ": the target field is not a collection.");
                }
                String currentProblem = FieldValueCompatibility.problem(
                        targetSchema,
                        candidate.proposedValue());
                if (currentProblem != null) {
                    throw new IllegalArgumentException("Cannot apply " + candidate.field()
                            + ": " + currentProblem);
                }
                Object value = approvedValue(candidate, fieldDecision.action());
                quiz.curation.CorrectionPolicy policy = policy(fieldDecision.action());
                EnrichmentProposal.SourceRef candidateSource = candidate.source();
                quiz.curation.ValueSource source = candidateSource == null ? null
                        : new quiz.curation.ValueSource(
                        candidateSource.kind(), candidateSource.sourceId(),
                        candidateSource.propertyId(), candidateSource.propertyLabel(),
                        candidateSource.direction(), candidateSource.recordUrl());
                curation.put(type, targetId, candidate.field(), value,
                        origin, null, policy, source);
            }

            EnrichmentProposal.MediaCandidate media = decision.media();
            if (media != null) {
                EnrichmentProposal.SourceRef mediaSource = media.source();
                quiz.curation.ValueSource source = mediaSource == null ? null
                        : new quiz.curation.ValueSource(
                        mediaSource.kind(), mediaSource.sourceId(),
                        mediaSource.propertyId(), mediaSource.propertyLabel(),
                        mediaSource.direction(), mediaSource.recordUrl());
                curation.put(type, targetId, media.field(), media.imageUrl(), origin,
                        media.collection()
                                ? Correction.MEDIA_COLLECTION : Correction.MEDIA,
                        quiz.curation.CorrectionPolicy.FILL_IF_EMPTY, source);
            }
            curation.save();
        } catch (IOException | RuntimeException ex) {
            for (String field : affectedFields(decision)) {
                curation.remove(type, targetId, field);
            }
            previousCorrections.forEach(curation::restore);
            curation.removeIdentityLink(type, targetId, sourceKind);
            previousLinks.forEach(curation::putIdentityLink);
            throw ex;
        }

        quiz.curation.IdentitySources.apply(domain.instances(), curation.identityLinks());
        return Corrections.apply(domain.instances(), List.of(curation));
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
            case FILL_IF_EMPTY, IGNORE -> quiz.curation.CorrectionPolicy.FILL_IF_EMPTY;
        };
    }
}
