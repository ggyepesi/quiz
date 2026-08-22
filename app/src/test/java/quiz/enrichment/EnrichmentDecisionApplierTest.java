package quiz.enrichment;

import datasource.enrichment.EnrichmentProposal;

import datasource.SourceRef;
import datasource.EntityRef;
import datasource.evidence.EvidenceFragment;
import datasource.evidence.ExtractedClaim;
import datasource.evidence.SourceDocument;

import objectview.field.FieldKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import objectview.Viewable;
import quiz.curation.ManualCuration;
import quiz.curation.CurationStaging;
import quiz.curation.Corrections;
import domain.DomainField;
import domain.DomainModel;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataMediaValue;

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
                        "7", new datasource.evidence.ContentDigest("sha256", "abc"),
                        "2026-08-21T09:00:00Z"),
                        "Biography", "Born as Jane Doe.")),
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
                        new datasource.evidence.ContentDigest("sha256", "def"),
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
        WikidataMediaValue applied =
                assertInstanceOf(WikidataMediaValue.class, person.get("image"));
        assertEquals(media.imageUrl(), applied.mediaUrl());

        ManualCuration reloaded =
                new ManualCuration(curation.file()).load();
        assertEquals(1, reloaded.identityLinks().size());
        assertEquals("421", reloaded.identityLinks().get(0).sourceId());
        assertEquals(sourceClaim.assessmentId(), reloaded.identityLinks().get(0)
                .evidence().get(0).assessmentId());
        assertEquals("local-person-1", reloaded.identityLinks().get(0).targetId());
        assertEquals("nobelprize.org", reloaded.corrections().get(0).origin());
        assertEquals(quiz.curation.CorrectionPolicy.FILL_IF_EMPTY,
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
