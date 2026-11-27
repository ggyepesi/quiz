package domain.enrichment;

import domain.enrichment.EnrichmentProposal;

import domain.datasource.SourceRef;
import domain.config.OwnedComponentSite;

import domain.curation.Correction;
import domain.curation.CorrectionPolicy;
import domain.curation.Corrections;
import domain.curation.CuratedInstance;
import domain.curation.Curatable;
import domain.curation.CurationStaging;
import domain.curation.IdentityLink;
import domain.curation.ManualCuration;
import domain.curation.ValueSource;
import domain.DomainModel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import domain.DomainSchemas;

/**
 * Persists an approved enrichment decision as identity metadata plus ordinary
 * correction overlays. Saving happens before the live domain is changed.
 */
public final class EnrichmentDecisionApplier {

    private EnrichmentDecisionApplier() { }

    /** Result of one reviewed staging transaction as it appears in the live workbench. */
    public record StagedPreview(int stagedRecords, int appliedValues,
                                List<domain.curation.CuratedInstances.Unresolved> unresolvedReferences) {
        public StagedPreview {
            unresolvedReferences = unresolvedReferences == null
                    ? List.of() : List.copyOf(unresolvedReferences);
        }
    }

    /** Stage a reviewed batch, materialize its accepted typed instances, then replay the
     * staged field corrections over that same live pool. Review UIs call this one method
     * so "staged" cannot mean that the sidecar changed while the displayed group did not. */
    public static StagedPreview stageAndPreview(
            DomainModel domain, CurationStaging staging,
            List<EnrichmentDecision> decisions) {
        return stageBatch(domain, staging, decisions).preview();
    }

    private record Staged(StagedPreview preview, Prepared prepared) { }

    private static Staged stageBatch(
            DomainModel domain, CurationStaging staging,
            List<EnrichmentDecision> decisions) {
        if (domain == null || staging == null) {
            return new Staged(new StagedPreview(0, 0, List.of()),
                    new Prepared(List.of(), List.of(), List.of()));
        }
        List<EnrichmentDecision> accepted = decisions == null ? List.of()
                : decisions.stream().filter(java.util.Objects::nonNull).toList();
        boolean createsInstances = accepted.stream()
                .anyMatch(decision -> !decision.instances().isEmpty());
        domain.curation.CuratedInstanceSink sink =
                domain.capability(domain.curation.CuratedInstanceSink.class);
        if (createsInstances && sink == null) {
            throw new IllegalStateException(
                    "This domain cannot add reviewed source-derived instances.");
        }
        Prepared prepared = prepareAll(domain, accepted);
        int staged = stage(staging, prepared);
        var unresolved = sink == null ? List.<domain.curation.CuratedInstances.Unresolved>of()
                : sink.materialize(prepared.instances()).unresolved();
        int applied = Corrections.apply(domain.instances(),
                List.of(() -> prepared.corrections()));
        return new Staged(new StagedPreview(staged, applied, unresolved), prepared);
    }

    /** Accept a reviewed batch and cross its durable save boundary in the same explicit
     * user action. Staging remains the transaction mechanism, not a second confirmation. */
    public static StagedPreview acceptAndSave(
            DomainModel domain, CurationStaging staging,
            List<EnrichmentDecision> decisions) throws IOException {
        Staged staged = stageBatch(domain, staging, decisions);
        // Persist only this accepted batch: other pending records in the shared session
        // (for example manual edits being collected in TransformApp) stay pending.
        if (staged.preview().stagedRecords() > 0) {
            Prepared batch = staged.prepared();
            staging.applyBatch(batch.corrections(), batch.identities(), batch.instances());
        }
        return staged.preview();
    }

    /** Put a reviewed decision into the shared curation transaction without crossing the
     * durable save boundary. Used by workflows that deliberately collect several edits
     * before one explicit save; review acceptance uses {@link #acceptAndSave}. */
    public static int stage(
            DomainModel domain, CurationStaging staging, EnrichmentDecision decision) {
        return stageAll(domain, staging,
                decision == null ? List.of() : List.of(decision));
    }

    /** Validate the complete review batch before staging any of it. One rejected value
     *  therefore produces one batch failure instead of leaving a partial transaction and
     *  one modal error per proposal. */
    public static int stageAll(
            DomainModel domain, CurationStaging staging,
            List<EnrichmentDecision> decisions) {
        if (domain == null || staging == null || decisions == null || decisions.isEmpty()) {
            return 0;
        }
        return stage(staging, prepareAll(domain, decisions));
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
        Prepared prepared = prepare(domain, decision, acceptedCorrections(
                domain, curation, List.of(decision)));
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
        List<CuratedInstance> previousInstances = curation.instances().stream()
                .filter(existing -> decision.instances().stream().anyMatch(candidate ->
                        candidate.type().equals(existing.type())
                                && candidate.id().equals(existing.id())))
                .toList();

        try {
            prepared.identities().forEach(curation::putIdentityLink);
            for (Correction correction : prepared.corrections()) {
                curation.put(correction.type(), correction.qid(), correction.field(),
                        correction.value(), correction.origin(), correction.valueKind(),
                        correction.policy(), correction.source(),
                        correction.referenceType());
            }
            prepared.instances().forEach(curation::putInstance);
            curation.save();
        } catch (IOException | RuntimeException ex) {
            for (String field : affectedFields(decision)) {
                curation.remove(type, targetId, field);
            }
            previousCorrections.forEach(curation::restore);
            sourceKinds.forEach(kind -> curation.removeIdentityLink(type, targetId, kind));
            previousLinks.forEach(curation::putIdentityLink);
            for (CuratedInstance instance : prepared.instances()) {
                curation.removeInstance(instance.type(), instance.id());
            }
            previousInstances.forEach(curation::putInstance);
            throw ex;
        }

        return Corrections.apply(domain.instances(), List.of(curation));
    }

    private static Prepared prepare(DomainModel domain, EnrichmentDecision decision,
            Map<Corrections.Target, Correction> acceptedCorrections) {
        String type = decision.subject().type();
        String targetId = decision.subject().targetId();
        List<IdentityLink> identities = decision.identities().stream().map(identity -> {
            String sourceKind = identity.source().kind();
            return new IdentityLink(type, targetId, sourceKind, identity.source().sourceId(),
                    identity.source().recordUrl(), identity.canonicalName(), origin(sourceKind),
                    identity.evidence());
        }).toList();
        List<Correction> corrections = new ArrayList<>();
        List<CuratedInstance> instances = decision.instances().stream()
                .map(EnrichmentDecisionApplier::curatedInstance).toList();
        for (EnrichmentDecision.FieldDecision fieldDecision : decision.fields()) {
            EnrichmentProposal.FieldCandidate candidate = fieldDecision.candidate();
            if (!candidate.compatible()) throw new IllegalArgumentException("Cannot apply "
                    + candidate.field() + ": " + candidate.compatibilityError());
            var site = OwnedComponentSite.parse(type);
            objectview.field.FieldRef schema = DomainSchemas.resolve(
                    domain, site == null ? type : site.targetClass(), candidate.field());
            if (fieldDecision.action() == EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION
                    && (schema == null || !schema.collection())) {
                throw new IllegalArgumentException("Cannot add to " + candidate.field()
                        + ": the target field is not a collection.");
            }
            String problem = FieldValueCompatibility.problem(
                    domain, schema, candidate.proposedValue());
            if (problem != null) throw new IllegalArgumentException("Cannot apply "
                    + candidate.field() + ": " + problem);
            Object value = approvedValue(candidate, fieldDecision.action());
            validateEvidenceEntity(candidate, value);
            SourceRef candidateSource = candidate.source();
            ValueSource source = valueSource(candidateSource, candidate.evidence());
            StoredValue stored = storedValue(value, candidate.targetCollection());
            Correction existing = candidate.sourceUpdate() ? acceptedCorrections.get(
                    new Corrections.Target(type, targetId, candidate.field())) : null;
            if (existing != null && type.equals(existing.type())
                    && java.util.Objects.equals(existing.value(), stored.value())
                    && java.util.Objects.equals(existing.valueKind(), stored.kind())
                    && existing.referenceType().equals(stored.referenceType())) {
                corrections.add(new Correction(type, targetId, candidate.field(), existing.value(),
                        existing.origin(), existing.valueKind(), existing.effectivePolicy(), source,
                        existing.referenceType()));
                continue;
            }
            corrections.add(new Correction(type, targetId, candidate.field(), stored.value(),
                    origin(candidateSource.kind()), stored.kind(), policy(fieldDecision.action()),
                    source, stored.referenceType()));
        }
        EnrichmentProposal.MediaCandidate media = decision.media();
        if (media != null) {
            corrections.add(new Correction(type, targetId, media.field(), media.imageUrl(),
                    origin(media.source().kind()), media.collection()
                    ? Correction.MEDIA_COLLECTION : Correction.MEDIA,
                    CorrectionPolicy.FILL_IF_EMPTY,
                    valueSource(media.source(), media.evidence())));
        }
        return new Prepared(List.copyOf(corrections), identities, instances);
    }

    /** Prepare the whole review before mutating staging, then retain the exact batch so
     * preview applies only what this action accepted rather than replaying older work. */
    private static Prepared prepareAll(
            DomainModel domain, List<EnrichmentDecision> decisions) {
        List<Correction> corrections = new ArrayList<>();
        List<IdentityLink> identities = new ArrayList<>();
        List<CuratedInstance> instances = new ArrayList<>();
        var curatable = domain.capability(Curatable.class);
        var accepted = acceptedCorrections(
                domain, curatable == null ? null : curatable.curation(), decisions);
        for (EnrichmentDecision decision : decisions == null
                ? List.<EnrichmentDecision>of() : decisions) {
            if (decision == null) continue;
            Prepared prepared = prepare(domain, decision, accepted);
            corrections.addAll(prepared.corrections());
            identities.addAll(prepared.identities());
            instances.addAll(prepared.instances());
        }
        return new Prepared(List.copyOf(corrections), List.copyOf(identities),
                List.copyOf(instances));
    }

    private static Map<Corrections.Target, Correction> acceptedCorrections(
            DomainModel domain, ManualCuration curation, List<EnrichmentDecision> decisions) {
        if (curation == null || decisions == null) return Map.of();
        var fields = decisions.stream()
                .filter(java.util.Objects::nonNull)
                .flatMap(decision -> decision.fields().stream())
                .map(EnrichmentDecision.FieldDecision::candidate)
                .filter(EnrichmentProposal.FieldCandidate::sourceUpdate)
                .map(EnrichmentProposal.FieldCandidate::field)
                .collect(java.util.stream.Collectors.toSet());
        return Corrections.acceptedFieldCorrections(domain.instances(), curation, fields);
    }

    private static int stage(CurationStaging staging, Prepared prepared) {
        prepared.identities().forEach(staging::stage);
        prepared.corrections().forEach(staging::stage);
        prepared.instances().forEach(staging::stage);
        return prepared.corrections().size() + prepared.identities().size()
                + prepared.instances().size();
    }

    private static ValueSource valueSource(
            SourceRef source, List<domain.datasource.evidence.ExtractedClaim> evidence) {
        return source == null ? null : new ValueSource(source.kind(),
                source.sourceId(), source.propertyId(), source.propertyLabel(),
                source.direction(), source.recordUrl(), evidence);
    }

    private static CuratedInstance curatedInstance(
            EnrichmentProposal.InstanceCandidate candidate) {
        List<CuratedInstance.FieldValue> fields = candidate.fields().stream()
                .map(field -> field.references().isEmpty()
                        ? CuratedInstance.FieldValue.literal(
                                field.field(), field.value())
                        : CuratedInstance.FieldValue.references(field.field(),
                                field.references().stream().map(reference ->
                                        new CuratedInstance.Reference(
                                                reference.type(), reference.id())).toList()))
                .toList();
        return new CuratedInstance(candidate.type(), candidate.id(),
                candidate.displayName(), fields,
                valueSource(candidate.source(), candidate.evidence()));
    }

    private record Prepared(List<Correction> corrections, List<IdentityLink> identities,
                            List<CuratedInstance> instances) { }

    private record StoredValue(Object value, String kind, String referenceType) { }

    private static StoredValue storedValue(Object value, boolean collection) {
        if (value instanceof java.util.List<?> values && !values.isEmpty()) {
            if (values.stream().allMatch(objectview.Viewable.class::isInstance)) {
                return new StoredValue(values.stream().map(objectview.Viewable.class::cast)
                        .map(ref -> java.util.Map.of("type", ref.identityTypeName(),
                                "id", ref.getIdentifier())).toList(),
                        Correction.REFERENCE_COLLECTION, "");
            }
            if (values.stream().allMatch(aux.FlexibleDate.class::isInstance)) {
                return new StoredValue(values.stream().map(aux.FlexibleDate.class::cast)
                        .map(aux.FlexibleDate::format).toList(), Correction.DATE_COLLECTION, "");
            }
            if (values.stream().allMatch(objectview.media.MediaValue.class::isInstance)) {
                return new StoredValue(values.stream().map(objectview.media.MediaValue.class::cast)
                        .map(objectview.media.MediaValue::mediaUrl).toList(),
                        Correction.MEDIA_COLLECTION, "");
            }
        }
        if (value instanceof aux.FlexibleDate date) {
            return new StoredValue(date.format(), Correction.DATE, "");
        }
        if (value instanceof objectview.media.MediaValue media) {
            return new StoredValue(media.mediaUrl(), Correction.MEDIA, "");
        }
        if (referenceId(value) != null) {
            return new StoredValue(referenceId(value), collection
                    ? Correction.REFERENCE_COLLECTION : Correction.REFERENCE, referenceType(value));
        }
        return new StoredValue(value, null, "");
    }

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

    /** A reference identifies one configured type as well as one source identifier. */
    private static String referenceType(Object value) {
        if (value instanceof objectview.Viewable target) {
            return target.identityTypeName();
        }
        if (value instanceof java.util.Collection<?> values && values.size() == 1) {
            return referenceType(values.iterator().next());
        }
        return "";
    }

    private static void validateEvidenceEntity(
            EnrichmentProposal.FieldCandidate candidate, Object value) {
        String actual = (String) referenceId(value);
        for (domain.datasource.evidence.ExtractedClaim claim : candidate.evidence()) {
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

    private static CorrectionPolicy policy(
            EnrichmentProposal.ReviewAction action) {
        if (action == null) return CorrectionPolicy.FILL_IF_EMPTY;
        return switch (action) {
            case REPLACE -> CorrectionPolicy.REPLACE;
            case ADD_TO_COLLECTION -> CorrectionPolicy.ADD_TO_COLLECTION;
            case CORROBORATE -> CorrectionPolicy.EVIDENCE_ONLY;
            case FILL_IF_EMPTY, IGNORE -> CorrectionPolicy.FILL_IF_EMPTY;
        };
    }
}
