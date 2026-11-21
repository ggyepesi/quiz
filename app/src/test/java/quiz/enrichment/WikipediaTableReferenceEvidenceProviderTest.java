package quiz.enrichment;

import datasource.enrichment.EnrichmentProposal;
import datasource.evidence.WikipediaArticle;
import datasource.wikipedia.WikipediaDatasourceProvider;
import objectview.field.FieldKind;
import objectview.field.FieldRef;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import work.Query;
import work.QueryContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikipediaTableReferenceEvidenceProviderTest {

    @Test void proposesOnlyLoadedTypedTargetsLinkedFromTheConfiguredColumn()
            throws Exception {
        WikidataDynamicObject ra = deity("Q2831", "Ra", "Ra");
        WikipediaTableReferenceEvidenceProvider provider =
                new WikipediaTableReferenceEvidenceProvider(
                        new WikipediaDatasourceProvider.ArticleTableColumnLinkSpec(
                                "Description > List of names", "Assessors",
                                "Name of the deity"),
                        List.of(ra), uri -> """
                        {"query":{"pages":[{"title":"Unknown deity","missing":true}]}}
                        """);
        EnrichmentRequest request = request(List.of(), article());

        Query<EnrichmentProposal> query = provider.discover(request);
        EnrichmentProposal result = query.execute(new QueryContext());

        assertEquals(1, result.fields().size());
        assertSame(ra, result.fields().getFirst().proposedValue());
        assertEquals(EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION,
                result.fields().getFirst().suggestedAction());
        assertEquals("Q2831", result.fields().getFirst().evidence().getFirst()
                .proposedEntity().id());
        assertTrue(query.summary(result).contains("2 retained link(s)"),
                query.summary(result));
        assertTrue(query.summary(result).contains("1 link(s) resolved"),
                query.summary(result));
        assertTrue(query.summary(result).contains(
                "1 did not resolve to a loaded target"), query.summary(result));
    }

    @Test void aRedirectedOrDifferentlySpelledLinkStillResolvesToALoadedTarget()
            throws Exception {
        WikidataDynamicObject osiris = deity("Q46491", "Osiris", "Osiris");
        WikipediaTableReferenceEvidenceProvider provider =
                new WikipediaTableReferenceEvidenceProvider(
                        new WikipediaDatasourceProvider.ArticleTableColumnLinkSpec(
                                "Description > List of names", "Assessors",
                                "Name of the deity"),
                        List.of(osiris), uri -> """
                        {"query":{"redirects":[{"from":"Wesir","to":"Osiris"}],
                        "pages":[{"title":"Osiris",
                        "pageprops":{"wikibase_item":"Q46491"}}]}}
                        """);

        EnrichmentProposal result = provider.discover(request(
                        List.of(), article("the lord of the dead", "Wesir")))
                .execute(new QueryContext());

        assertEquals(1, result.fields().size());
        assertSame(osiris, result.fields().getFirst().proposedValue());
    }

    @Test void doesNotProposeAConfiguredColumnTargetAlreadyInTheCollection()
            throws Exception {
        WikidataDynamicObject ra = deity("Q2831", "Ra", "Ra");
        WikipediaTableReferenceEvidenceProvider provider =
                new WikipediaTableReferenceEvidenceProvider(
                        new WikipediaDatasourceProvider.ArticleTableColumnLinkSpec(
                                "Description > List of names", "Assessors",
                                "Name of the deity"),
                        List.of(ra), uri -> "{}");

        EnrichmentProposal result = provider.discover(request(List.of(ra), article()))
                .execute(new QueryContext());

        assertEquals(List.of(), result.fields());
    }

    private static EnrichmentRequest request(Object current, WikipediaArticle article) {
        FieldRef schema = FieldRef.described("members", FieldKind.COLLECTION,
                FieldKind.REFERENCE, "List<EgyptianDeity>", true, true,
                "EgyptianDeity", false, false, false, false, "", false);
        return new EnrichmentRequest(
                new EnrichmentProposal.Subject("DeityGroup", "Q9133532", "Q9133532",
                        "Assessors of Maat"),
                "members", true, List.of(), schema, current, List.of(), null, article,
                null);
    }

    private static WikipediaArticle article() {
        var parsed = wikipedia.WikipediaArticleClient.parseArticleText("""
                <h2>Description</h2><h3>List of names</h3>
                <table><caption>Assessors</caption>
                  <tr><th>Name of the deity</th><th>Task</th></tr>
                  <tr><td><a href='/wiki/Ra'>Ra</a></td><td>truth</td></tr>
                  <tr><td><a href='/wiki/Unknown_deity'>Unknown deity</a></td>
                      <td><a href='/wiki/Maat'>Maat</a></td></tr>
                </table>
                """);
        return new WikipediaArticle(
                wikipedia.WikipediaArticleClient.sourceDocument(
                        "Assessors of Maat", "1", parsed.text(), List.of(),
                        parsed.links()),
                parsed.text(), List.of(), parsed.structuredSections());
    }

    private static WikipediaArticle article(String text, String targetTitle) {
        String html = "<h2>Description</h2><h3>List of names</h3>"
                + "<table><caption>Assessors</caption>"
                + "<tr><th>Name of the deity</th></tr><tr><td><a href='/wiki/"
                + targetTitle + "'>" + text + "</a></td></tr></table>";
        var parsed = wikipedia.WikipediaArticleClient.parseArticleText(html);
        return new WikipediaArticle(
                wikipedia.WikipediaArticleClient.sourceDocument(
                        "Assessors of Maat", "1", parsed.text(), List.of(),
                        parsed.links()),
                parsed.text(), List.of(), parsed.structuredSections());
    }

    private static WikidataDynamicObject deity(String qid, String label, String title) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, label);
        value.type("EgyptianDeity");
        value.enwikiTitle(title);
        return value;
    }
}
