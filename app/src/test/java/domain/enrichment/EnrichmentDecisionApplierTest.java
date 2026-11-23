package domain.enrichment;

import domain.enrichment.EnrichmentProposal;

import domain.datasource.SourceRef;
import domain.datasource.EntityRef;
import domain.datasource.evidence.EvidenceFragment;
import domain.datasource.evidence.ExtractedClaim;
import domain.datasource.evidence.SourceDocument;

import objectview.field.FieldKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import objectview.Viewable;
import domain.curation.ManualCuration;
import domain.curation.CurationStaging;
import domain.curation.Corrections;
import domain.curation.CorrectionPolicy;
import domain.DomainField;
import domain.DomainModel;
import domain.runtime.WikidataDynamicObject;
import domain.transform.DynamicViewable;
import domain.transform.app.ProductClass;
import domain.transform.app.ProductDomain;
import domain.transform.app.ProductField;
import domain.transform.app.ProductSchema;
import objectview.media.MediaValue;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import domain.DomainSchemas;

class EnrichmentDecisionApplierTest {

    @Test
    void textOnlyFieldEvidenceNeedsNoIdentityLink(@TempDir Path dir) throws Exception {
        WikidataDynamicObject person = new WikidataDynamicObject("local-person-1", "Someone");
        person.type("Person");
        DomainModel domain = new DomainModel() {
            @Override public List<String> types() { return List.of("Person"); }
            @Override public objectview.field.FieldSchema fieldSchema(String type) {
                return DomainSchemas.flatSchema(List.of(
                        new DomainField("Person", "birthName", false, false)));
            }
            @Override public Collection<? extends Viewable> instances() { return List.of(person); }
            @Override public Class<? extends Viewable> universe() { return WikidataDynamicObject.class; }
        };
        SourceRef source = new SourceRef("Archive", "record-1", "https://example.test/1",
                "birth-name");
        ExtractedClaim claim = new ExtractedClaim(
                new EntityRef("local", "local-person-1"), "birth-name", "Jane Doe", null,
                List.of(EvidenceFragment.excerpt(new SourceDocument(
                        "Archive", "record-1", "Record", "https://example.test/1",
                        "7", new domain.datasource.evidence.ContentDigest("sha256", "abc"),
                        "2026-08-21T09:00:00Z"),
                        "Biography", "Born as Jane Doe.", List.of(
                                new EvidenceFragment.Link("Jane Doe",
                                        "https://example.test/Jane_Doe", true)))),
                "exact-name", "v1", "people-a", 0.98, List.of());
        EnrichmentProposal.FieldCandidate candidate = new EnrichmentProposal.FieldCandidate(
                "birth-name", "", "birthName", null, "Jane Doe", source,
                EnrichmentProposal.ReviewAction.FILL_IF_EMPTY, null, false, List.of(claim));
        EnrichmentDecision decision = new EnrichmentDecision(
                new EnrichmentProposal.Subject("Person", "local-person-1", "", "Someone"),
                List.of(), List.of(new EnrichmentDecision.FieldDecision(
                        candidate, candidate.suggestedAction())), null);
        ManualCuration curation = new ManualCuration(dir.resolve("people.curation.json").toFile());

        CurationStaging staging = CurationStaging.forCuration(curation);
        assertEquals(1, EnrichmentDecisionApplier.stage(domain, staging, decision));
        assertTrue(!curation.file().exists(), "staging must not cross the save boundary");
        assertEquals(1, staging.size());
        staging.apply();
        assertEquals(1, Corrections.apply(domain.instances(), List.of(curation)));
        assertEquals("Jane Doe", person.get("birthName"));
        ManualCuration reloaded = new ManualCuration(curation.file()).load();
        assertTrue(reloaded.identityLinks().isEmpty());
        assertEquals(claim.assessmentId(), reloaded.corrections().get(0)
                .source().evidence().get(0).assessmentId());
        assertTrue(reloaded.corrections().get(0).source().evidence().get(0)
                .evidence().getFirst().links().getFirst().identifying(),
                "the source-structural member-link marker must survive staging and save");
    }

    @Test
    void approvedIdentityAndMediaPersistAndApplyTogether(@TempDir Path dir) throws Exception {
        WikidataDynamicObject person = new WikidataDynamicObject("local-person-1", "George D. Snell");
        person.type("Person");
        DomainModel domain = domain(person);
        ManualCuration curation =
                new ManualCuration(new File(dir.toFile(), "people.curation.json"));

        SourceRef source = new SourceRef(
                "NobelPrize.org", "421",
                "https://www.nobelprize.org/prizes/medicine/1980/snell/facts/");
        ExtractedClaim sourceClaim = new ExtractedClaim(
                new EntityRef("local", "local-person-1"), "identity", "George Davis Snell",
                null, List.of(EvidenceFragment.excerpt(new SourceDocument(
                        "NobelPrize.org", "421", "George D. Snell facts",
                        source.recordUrl(), "9",
                        new domain.datasource.evidence.ContentDigest("sha256", "def"),
                        "2026-08-21T09:00:00Z"), "Facts", "George Davis Snell")),
                "record-fields", "v1", "people-a", .99, List.of());
        EnrichmentProposal.IdentityCandidate identity =
                new EnrichmentProposal.IdentityCandidate(
                        "nobel-421", "George Davis Snell",
                        List.of("George D. Snell"), "American geneticist",
                        source, 0.99, List.of("Nobel laureate ID 421"),
                        List.of(sourceClaim));
        String imageUrl = "https://www.nobelprize.org/images/snell-portrait.jpg";
        ExtractedClaim mediaClaim = new ExtractedClaim(
                new EntityRef("local", "local-person-1"), "image", imageUrl, null,
                sourceClaim.evidence(), "og:image", "v1", "people-a", .95, List.of());
        EnrichmentProposal.MediaCandidate media = new EnrichmentProposal.MediaCandidate(
                "portrait", identity.candidateId(), "image",
                imageUrl,
                "", source, "og:image", 0.95,
                "Nobel Foundation archive", "", false, List.of(mediaClaim));
        EnrichmentDecision decision = new EnrichmentDecision(
                new EnrichmentProposal.Subject(
                        "Person", "local-person-1", "Q1", "George D. Snell"),
                identity, List.of(), media);

        assertEquals(1, EnrichmentDecisionApplier.apply(domain, curation, decision));
        MediaValue applied = assertInstanceOf(MediaValue.class, person.get("image"));
        assertEquals(media.imageUrl(), applied.mediaUrl());

        ManualCuration reloaded =
                new ManualCuration(curation.file()).load();
        assertEquals(1, reloaded.identityLinks().size());
        assertEquals("421", reloaded.identityLinks().get(0).sourceId());
        assertEquals(sourceClaim.assessmentId(), reloaded.identityLinks().get(0)
                .evidence().get(0).assessmentId());
        assertEquals("local-person-1", reloaded.identityLinks().get(0).targetId());
        assertEquals("nobelprize.org", reloaded.corrections().get(0).origin());
        assertEquals(CorrectionPolicy.FILL_IF_EMPTY,
                reloaded.corrections().get(0).policy());
        assertEquals("421", reloaded.corrections().get(0).source().entityId());
        assertEquals(mediaClaim.assessmentId(), reloaded.corrections().get(0)
                .source().evidence().get(0).assessmentId());
    }

    @Test
    void rejectsAddToCollectionForAScalarField(@TempDir Path dir) {
        WikidataDynamicObject person = new WikidataDynamicObject("Q1", "One");
        person.type("Person");
        person.put("population", 1L);
        DomainModel domain = new DomainModel() {
            @Override public List<String> types() { return List.of("Person"); }
            @Override public objectview.field.FieldSchema fieldSchema(String type) {
                return DomainSchemas.flatSchema(List.of(
                        new DomainField("Person", "population", false, false,
                                FieldKind.ORDERED)));
            }
            @Override public Collection<? extends Viewable> instances() {
                return List.of(person);
            }
            @Override public Class<? extends Viewable> universe() {
                return WikidataDynamicObject.class;
            }
        };
        ManualCuration curation =
                new ManualCuration(new File(dir.toFile(), "people.curation.json"));
        SourceRef source =
                new SourceRef("Wikidata", "Q1", "url", "P1082");
        EnrichmentProposal.IdentityCandidate identity =
                new EnrichmentProposal.IdentityCandidate(
                        "wikidata", "One", List.of(), "", source, 1.0, List.of());
        EnrichmentProposal.FieldCandidate candidate =
                new EnrichmentProposal.FieldCandidate(
                        "population", "wikidata", "population", 1L, 2L,
                        source, EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION,
                        null, false);
        EnrichmentDecision decision = new EnrichmentDecision(
                new EnrichmentProposal.Subject("Person", "Q1", "Q1", "One"),
                identity,
                List.of(new EnrichmentDecision.FieldDecision(
                        candidate, EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION)),
                null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> EnrichmentDecisionApplier.apply(domain, curation, decision));
        assertEquals("Cannot add to population: the target field is not a collection.",
                error.getMessage());
        assertEquals(1L, person.get("population"));
        assertEquals(0, curation.corrections().size());
        assertEquals(0, curation.identityLinks().size());
    }

    @Test void validatesAWholeBatchBeforeStagingAnyDecision(@TempDir Path dir) {
        WikidataDynamicObject person = new WikidataDynamicObject("Q1", "One");
        person.type("Person");
        DomainModel domain = new DomainModel() {
            @Override public List<String> types() { return List.of("Person"); }
            @Override public objectview.field.FieldSchema fieldSchema(String type) {
                return DomainSchemas.flatSchema(List.of(
                        new DomainField("Person", "tags", false, true, FieldKind.TEXT),
                        new DomainField("Person", "population", false, false,
                                FieldKind.ORDERED)));
            }
            @Override public Collection<? extends Viewable> instances() {
                return List.of(person);
            }
            @Override public Class<? extends Viewable> universe() {
                return WikidataDynamicObject.class;
            }
        };
        SourceRef source = new SourceRef("Source", "1", "url", "property");
        EnrichmentProposal.FieldCandidate valid = new EnrichmentProposal.FieldCandidate(
                "valid", "", "tags", List.of(), "tag", source,
                EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION, null, true);
        EnrichmentProposal.FieldCandidate invalid = new EnrichmentProposal.FieldCandidate(
                "invalid", "", "population", null, "not a number", source,
                EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION, null, false);
        EnrichmentDecision first = new EnrichmentDecision(
                new EnrichmentProposal.Subject("Person", "Q1", "Q1", "One"),
                List.of(), List.of(new EnrichmentDecision.FieldDecision(
                        valid, valid.suggestedAction())), null);
        EnrichmentDecision second = new EnrichmentDecision(
                first.subject(), List.of(), List.of(new EnrichmentDecision.FieldDecision(
                        invalid, invalid.suggestedAction())), null);
        CurationStaging staging = CurationStaging.forCuration(new ManualCuration(
                dir.resolve("batch.curation.json").toFile()));

        assertThrows(IllegalArgumentException.class,
                () -> EnrichmentDecisionApplier.stageAll(
                        domain, staging, List.of(first, second)));
        assertEquals(0, staging.size(), "a rejected batch must stage no prefix");
    }

    /** Assessors of Maat are configured as AssessorOfMaat, a subclass of the type
     * accepted by DeityGroup.members. Staging used to compare the two class names
     * literally and reject the valid reviewed row as "not a DeityGroupMember". */
    @Test void aReferenceFieldAcceptsAConfiguredSubclassAndStillRejectsAnUnrelatedType(
            @TempDir Path dir) {
        DynamicViewable group = new DynamicViewable("Q9133532", "Assessors of Maat");
        group.type("DeityGroup");
        DynamicViewable assessor = new DynamicViewable("row-1", "First assessor");
        assessor.type("AssessorOfMaat");
        DynamicViewable unrelated = new DynamicViewable("Q5", "human");
        unrelated.type("Person");
        ProductDomain domain = new ProductDomain(new ProductSchema(List.of(
                new ProductClass("DeityGroup", "DeityGroup", List.of(
                        new ProductField("members", "List<DeityGroupMember>",
                                true, true, "DeityGroupMember", false))),
                new ProductClass("DeityGroupMember", "DeityGroupMember", List.of()),
                new ProductClass("AssessorOfMaat", "AssessorOfMaat",
                        "DeityGroupMember", List.of()),
                new ProductClass("Person", "Person", List.of())),
                List.of("DeityGroup", "AssessorOfMaat", "Person")),
                List.of(group), Viewable.class, null);
        SourceRef source = new SourceRef(
                "Wikipedia", "Assessors_of_Maat", "url", "members");
        CurationStaging staging = CurationStaging.forCuration(new ManualCuration(
                dir.resolve("assessors.curation.json").toFile()));

        assertEquals(1, EnrichmentDecisionApplier.stage(
                domain, staging, referenceDecision(group, assessor, source)));
        assertEquals("AssessorOfMaat",
                staging.corrections().getFirst().referenceType(),
                "the staged reference must retain the configured class as identity");
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> EnrichmentDecisionApplier.stage(
                        domain, staging, referenceDecision(group, unrelated, source)));
        assertEquals("Cannot apply members: Reference Person is not a DeityGroupMember",
                rejected.getMessage());
    }

    @Test void distinctReviewedMembersAndTheirInstancesSurviveOneBatchSave(
            @TempDir Path dir) throws Exception {
        DynamicViewable group = new DynamicViewable("Q9133532", "Assessors of Maat");
        group.type("DeityGroup");
        ProductDomain domain = new ProductDomain(new ProductSchema(List.of(
                new ProductClass("DeityGroup", "DeityGroup", List.of(
                        new ProductField("members", "List<DeityGroupMember>",
                                true, true, "DeityGroupMember", false))),
                new ProductClass("DeityGroupMember", "DeityGroupMember", List.of()),
                new ProductClass("AssessorOfMaat", "AssessorOfMaat",
                        "DeityGroupMember", List.of())),
                List.of("DeityGroup", "AssessorOfMaat")),
                List.of(group), Viewable.class, null);
        SourceRef source = new SourceRef(
                "Wikipedia", "Assessors_of_Maat", "url", "members");
        EnrichmentDecision first = instanceReferenceDecision(
                group, "row-1", "First assessor", source);
        EnrichmentDecision second = instanceReferenceDecision(
                group, "row-2", "Second assessor", source);
        ManualCuration curation = new ManualCuration(
                dir.resolve("assessors.curation.json").toFile());
        CurationStaging staging = CurationStaging.forCuration(curation);

        assertEquals(4, EnrichmentDecisionApplier.stageAll(
                domain, staging, List.of(first, second)));
        assertEquals(2, staging.corrections().size());
        assertEquals(2, staging.instances().size());
        staging.apply();

        ManualCuration saved = new ManualCuration(curation.file()).load();
        assertEquals(2, saved.corrections().size(),
                "members += row-1 and members += row-2 are independent decisions");
        assertEquals(2, saved.instances().size());
    }

    @Test void aPreviewReportsOnlyTheCurrentStagingActionsEffect(@TempDir Path dir) {
        WikidataDynamicObject person = new WikidataDynamicObject("Q1", "One");
        person.type("Person");
        DomainModel domain = new DomainModel() {
            @Override public List<String> types() { return List.of("Person"); }
            @Override public objectview.field.FieldSchema fieldSchema(String type) {
                return DomainSchemas.flatSchema(List.of(
                        new DomainField("Person", "note", false, false,
                                FieldKind.TEXT)));
            }
            @Override public Collection<? extends Viewable> instances() {
                return List.of(person);
            }
            @Override public Class<? extends Viewable> universe() {
                return WikidataDynamicObject.class;
            }
        };
        SourceRef source = new SourceRef("Wikipedia", "article", "url", "note");
        CurationStaging staging = CurationStaging.forCuration(new ManualCuration(
                dir.resolve("preview.curation.json").toFile()));

        EnrichmentDecision first = textDecision("Q1", "first", source);
        assertEquals(1, EnrichmentDecisionApplier.stageAndPreview(
                domain, staging, List.of(first)).appliedValues());
        objectview.field.FieldAccess.setPath(person, "note", null);

        EnrichmentDecision missingTarget = textDecision("Q-missing", "second", source);
        EnrichmentDecisionApplier.StagedPreview second =
                EnrichmentDecisionApplier.stageAndPreview(
                        domain, staging, List.of(missingTarget));

        assertEquals(0, second.appliedValues(),
                "the older staged correction must not be replayed and credited to this action");
        assertEquals(null, person.get("note"));
    }

    private static EnrichmentDecision referenceDecision(
            DynamicViewable owner, DynamicViewable member, SourceRef source) {
        EnrichmentProposal.FieldCandidate candidate =
                new EnrichmentProposal.FieldCandidate(
                        "member-" + member.getIdentifier(), "", "members", List.of(),
                        member, source, EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION,
                        null, true, List.of());
        return new EnrichmentDecision(new EnrichmentProposal.Subject(
                owner.typeName(), owner.getIdentifier(), owner.getIdentifier(),
                owner.getDisplayName()), List.of(),
                List.of(new EnrichmentDecision.FieldDecision(
                        candidate, candidate.suggestedAction())), null);
    }

    private static EnrichmentDecision textDecision(
            String targetId, String value, SourceRef source) {
        EnrichmentProposal.FieldCandidate candidate =
                new EnrichmentProposal.FieldCandidate(
                        "note:" + targetId, "", "note", null, value, source,
                        EnrichmentProposal.ReviewAction.FILL_IF_EMPTY,
                        null, false, List.of());
        return new EnrichmentDecision(new EnrichmentProposal.Subject(
                "Person", targetId, targetId, targetId), List.of(),
                List.of(new EnrichmentDecision.FieldDecision(
                        candidate, candidate.suggestedAction())), null);
    }

    private static EnrichmentDecision instanceReferenceDecision(
            DynamicViewable owner, String id, String label, SourceRef source) {
        DynamicViewable member = new DynamicViewable(id, label);
        member.type("AssessorOfMaat");
        EnrichmentDecision reference = referenceDecision(owner, member, source);
        EnrichmentProposal.InstanceCandidate instance =
                new EnrichmentProposal.InstanceCandidate(
                        "table-row-instance:" + id, "AssessorOfMaat", id, label,
                        List.of(), source, List.of(), true);
        return new EnrichmentDecision(reference.subject(), reference.identities(),
                reference.fields(), reference.media(), List.of(instance));
    }

    private static DomainModel domain(WikidataDynamicObject person) {
        return new DomainModel() {
            @Override public List<String> types() { return List.of("Person"); }
            @Override public objectview.field.FieldSchema fieldSchema(String type) {
                return DomainSchemas.flatSchema(List.of(
                        new DomainField("Person", "image", false, false,
                                FieldKind.MEDIA)));
            }
            @Override public Collection<? extends Viewable> instances() {
                return List.of(person);
            }
            @Override public Class<? extends Viewable> universe() {
                return WikidataDynamicObject.class;
            }
        };
    }
}
