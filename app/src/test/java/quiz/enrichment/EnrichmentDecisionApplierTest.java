package quiz.enrichment;

import objectview.field.FieldKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import objectview.Viewable;
import quiz.curation.ManualCuration;
import quiz.transform.ui.DomainField;
import quiz.transform.ui.DomainModel;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataMediaValue;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnrichmentDecisionApplierTest {

    @Test
    void approvedIdentityAndMediaPersistAndApplyTogether(@TempDir Path dir) throws Exception {
        WikidataDynamicObject person = new WikidataDynamicObject("local-person-1", "George D. Snell");
        person.type("Person");
        DomainModel domain = domain(person);
        ManualCuration curation =
                new ManualCuration(new File(dir.toFile(), "people.curation.json"));

        EnrichmentProposal.SourceRef source = new EnrichmentProposal.SourceRef(
                "NobelPrize.org", "421",
                "https://www.nobelprize.org/prizes/medicine/1980/snell/facts/");
        EnrichmentProposal.IdentityCandidate identity =
                new EnrichmentProposal.IdentityCandidate(
                        "nobel-421", "George Davis Snell",
                        List.of("George D. Snell"), "American geneticist",
                        source, 0.99, List.of("Nobel laureate ID 421"));
        EnrichmentProposal.MediaCandidate media = new EnrichmentProposal.MediaCandidate(
                "portrait", identity.candidateId(), "image",
                "https://www.nobelprize.org/images/snell-portrait.jpg",
                "", source, "og:image", 0.95,
                "Nobel Foundation archive", "", false);
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
        assertEquals("local-person-1", reloaded.identityLinks().get(0).targetId());
        assertEquals("nobelprize.org", reloaded.corrections().get(0).origin());
        assertEquals(quiz.curation.CorrectionPolicy.FILL_IF_EMPTY,
                reloaded.corrections().get(0).policy());
        assertEquals("421", reloaded.corrections().get(0).source().entityId());
    }

    @Test
    void rejectsAddToCollectionForAScalarField(@TempDir Path dir) {
        WikidataDynamicObject person = new WikidataDynamicObject("Q1", "One");
        person.type("Person");
        person.put("population", 1L);
        DomainModel domain = new DomainModel() {
            @Override public List<String> types() { return List.of("Person"); }
            @Override public objectview.field.FieldSchema fieldSchema(String type) {
                return quiz.transform.ui.DomainSchemas.flatSchema(List.of(
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
        EnrichmentProposal.SourceRef source =
                new EnrichmentProposal.SourceRef("Wikidata", "Q1", "url", "P1082");
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
                return quiz.transform.ui.DomainSchemas.flatSchema(List.of(
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
