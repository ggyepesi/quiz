package quiz.enrichment;

import datasource.enrichment.EnrichmentProposal;
import datasource.evidence.InfoboxParameters;
import datasource.evidence.WikipediaArticle;
import datasource.evidence.WikipediaFragment;
import objectview.field.FieldKind;
import objectview.field.FieldRef;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import work.QueryContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class WikipediaInfoboxReferenceEvidenceProviderTest {

    @Test void proposesOnlyLoadedTypedTargetsNamedByRetainedLinks() throws Exception {
        WikidataDynamicObject ra = deity("Q2831", "Ra", "Ra");
        WikidataDynamicObject thoth = deity("Q146321", "Thoth", "Thoth");
        WikipediaInfoboxReferenceEvidenceProvider provider =
                new WikipediaInfoboxReferenceEvidenceProvider(
                        "Rendered.Parents", List.of(ra, thoth), uri -> """
                        {"entities":{"Q1308":{"id":"Q1308",
                        "labels":{"en":{"language":"en","value":"Hathor"}}}}}
                        """);
        EnrichmentRequest request = request(List.of(), infobox());

        EnrichmentProposal proposal = provider.discover(request)
                .execute(new QueryContext());

        assertEquals(1, proposal.fields().size(),
                "Hathor is linked but not loaded, while unlinked Thoth is not evidence");
        assertSame(ra, proposal.fields().getFirst().proposedValue(),
                "the proposal reuses the loaded target instance");
        assertEquals(EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION,
                proposal.fields().getFirst().suggestedAction());
        assertEquals("Q2831", proposal.fields().getFirst().evidence().getFirst()
                .proposedEntity().id());
    }

    @Test void doesNotProposeAnAlreadyPresentTarget() throws Exception {
        WikidataDynamicObject ra = deity("Q2831", "Ra", "Ra");
        WikipediaInfoboxReferenceEvidenceProvider provider =
                new WikipediaInfoboxReferenceEvidenceProvider(
                        "Rendered.Parents", List.of(ra), uri -> "{}");

        EnrichmentProposal proposal = provider.discover(request(List.of(ra), infobox()))
                .execute(new QueryContext());

        assertEquals(List.of(), proposal.fields());
    }

    @Test void aLinkTitleMayResolveThroughItsQidButStillCannotCreateAnInstance()
            throws Exception {
        WikidataDynamicObject osiris = deity("Q46491", "Osiris", "");
        WikipediaFragment row = new WikipediaFragment("the lord of the dead",
                List.of(new WikipediaArticle.ArticleLink(
                        "the lord of the dead", "Osiris", 0, 20)), List.of());
        InfoboxParameters infobox = InfoboxParameters.fromEnglishWikipediaStructured(
                "Infobox deity", Map.of(), Map.of("Parents", row), "Maat", "43");
        WikipediaInfoboxReferenceEvidenceProvider provider =
                new WikipediaInfoboxReferenceEvidenceProvider(
                        "Rendered.Parents", List.of(osiris), uri -> """
                        {"entities":{"Q46491":{"id":"Q46491",
                        "labels":{"en":{"language":"en","value":"Osiris"}}}}}
                        """);

        EnrichmentProposal proposal = provider.discover(request(List.of(), infobox))
                .execute(new QueryContext());

        assertEquals(1, proposal.fields().size());
        assertSame(osiris, proposal.fields().getFirst().proposedValue());
    }

    private static EnrichmentRequest request(Object current, InfoboxParameters infobox) {
        FieldRef schema = FieldRef.described("parents", FieldKind.COLLECTION,
                FieldKind.REFERENCE, "List<EgyptianDeity>", true, true,
                "EgyptianDeity", false, false, false, false, "", false);
        return new EnrichmentRequest(
                new EnrichmentProposal.Subject("EgyptianDeity", "Q185101", "Q185101",
                        "Maat"),
                "parents", true, List.of(), schema, current, List.of(), null, null,
                infobox);
    }

    private static InfoboxParameters infobox() {
        WikipediaFragment row = new WikipediaFragment("Amun-Ra, Hathor and Thoth",
                List.of(new WikipediaArticle.ArticleLink("Amun-Ra", "Ra", 0, 7),
                        new WikipediaArticle.ArticleLink("Hathor", "Hathor", 9, 15)),
                List.of());
        return InfoboxParameters.fromEnglishWikipediaStructured("Infobox deity", Map.of(),
                Map.of("Parents", row), "Maat", "42");
    }

    private static WikidataDynamicObject deity(String qid, String label, String title) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, label);
        value.type("EgyptianDeity");
        value.enwikiTitle(title);
        return value;
    }
}
