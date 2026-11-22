package quiz.enrichment;

import datasource.enrichment.EnrichmentProposal;
import datasource.evidence.WikipediaArticle;
import datasource.wikipedia.WikipediaDatasourceProvider;
import objectview.field.FieldKind;
import objectview.field.FieldRef;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import work.QueryContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A configured table row is a typed, reviewable instance rather than an invented QID. */
class WikipediaTableRowInstanceEvidenceProviderTest {

    @Test void everyDataRowBecomesOneBulkSafeTypedInstanceWithCellsAndOwner()
            throws Exception {
        WikidataDynamicObject ra = new WikidataDynamicObject("Q2831", "Ra");
        ra.type("EgyptianDeity");
        ra.enwikiTitle("Ra");
        var spec = new WikipediaDatasourceProvider.ArticleTableRowInstanceSpec(
                "Description > List of names", "Assessors", "Name of the deity",
                "AssessorOfMaat", "rowDetails", "groups", "deities");
        var provider = new WikipediaTableRowInstanceEvidenceProvider(
                spec, List.of(ra), "EgyptianDeity", uri -> """
                        {"query":{"pages":[{"title":"Unknown deity","missing":true}]}}
                        """);

        EnrichmentProposal result = provider.discover(request(article()))
                .execute(new QueryContext());

        assertEquals(2, result.instances().size());
        assertEquals(2, result.fields().size(),
                "each new instance is added to the configured owner field");
        EnrichmentProposal.InstanceCandidate first = result.instances().getFirst();
        assertEquals("AssessorOfMaat", first.type());
        assertEquals("Ra", first.displayName());
        assertTrue(first.bulkSafe(), "the exact configured rows can be staged together");
        assertEquals(List.of("Name of the deity: Ra", "Task: truth"),
                first.fields().get(0).value());
        assertEquals(new EnrichmentProposal.TypedReference("DeityGroup", "Q9133532"),
                first.fields().get(1).references().getFirst());
        assertEquals(new EnrichmentProposal.TypedReference("EgyptianDeity", "Q2831"),
                first.fields().get(2).references().getFirst());
        assertEquals(2, result.instances().get(1).fields().size(),
                "an unresolved display-cell link creates no deity instance or reference");
        assertTrue(result.instances().stream().map(EnrichmentProposal.InstanceCandidate::id)
                .allMatch(id -> id.startsWith("wikipedia-table-row:")));
    }

    @Test void aRowLinkedToAnExistingMemberDoesNotCreateADuplicateWrapper()
            throws Exception {
        WikidataDynamicObject ra = new WikidataDynamicObject("Q2831", "Ra");
        ra.type("EgyptianDeity");
        ra.enwikiTitle("Ra");
        var spec = new WikipediaDatasourceProvider.ArticleTableRowInstanceSpec(
                "Description > List of names", "Assessors", "Name of the deity",
                "AssessorOfMaat", "rowDetails", "groups", "deities");
        var provider = new WikipediaTableRowInstanceEvidenceProvider(
                spec, List.of(ra), "EgyptianDeity", uri -> """
                        {"query":{"pages":[{"title":"Unknown deity","missing":true}]}}
                        """);

        EnrichmentProposal result = provider.discover(request(article(), List.of(ra)))
                .execute(new QueryContext());

        assertEquals(1, result.instances().size());
        assertEquals("Unknown deity", result.instances().getFirst().displayName());
        assertEquals(1, result.fields().size());
    }

    /** An edit to another cell of an accepted row once proposed a second instance beside
     *  it, because the identity hashed the whole row. The display cell and its occurrence
     *  are the identity; two rows sharing a display label still stay apart. */
    @Test void editingAnotherCellKeepsTheRowIdentityAndEqualLabelsStayApart()
            throws Exception {
        var spec = new WikipediaDatasourceProvider.ArticleTableRowInstanceSpec(
                "Description > List of names", "Assessors", "Name of the deity",
                "AssessorOfMaat", "rowDetails", "groups", "");
        var provider = new WikipediaTableRowInstanceEvidenceProvider(
                spec, List.of(), "", uri -> "{\"query\":{\"pages\":[]}}");

        List<String> before = ids(provider.discover(request(article(
                "truth", "judgement", "Unknown deity"))).execute(new QueryContext()));
        List<String> afterEdit = ids(provider.discover(request(article(
                "truthfulness", "judgement", "Unknown deity"))).execute(new QueryContext()));
        List<String> sharedLabel = ids(provider.discover(request(article(
                "truth", "judgement", "Ra"))).execute(new QueryContext()));

        assertEquals(before, afterEdit, "a Task edit is the same row");
        assertEquals(2, sharedLabel.stream().distinct().count(),
                "two rows labelled Ra are two instances");
        assertEquals(before.getFirst(), sharedLabel.getFirst(),
                "the first Ra keeps its identity when a second Ra row appears");
    }

    private static List<String> ids(EnrichmentProposal result) {
        return result.instances().stream()
                .map(EnrichmentProposal.InstanceCandidate::id).toList();
    }

    private static WikipediaArticle article(
            String firstTask, String secondTask, String secondName) {
        var parsed = wikipedia.WikipediaArticleClient.parseArticleText("""
                <h2>Description</h2><h3>List of names</h3>
                <table><caption>Assessors</caption>
                  <tr><th>Name of the deity</th><th>Task</th></tr>
                  <tr><td>Ra</td><td>%s</td></tr>
                  <tr><td>%s</td><td>%s</td></tr>
                </table>
                """.formatted(firstTask, secondName, secondTask));
        return new WikipediaArticle(
                wikipedia.WikipediaArticleClient.sourceDocument(
                        "Assessors of Maat", "1", parsed.text(), List.of(), parsed.links()),
                parsed.text(), List.of(), parsed.structuredSections());
    }

    private static EnrichmentRequest request(WikipediaArticle article) {
        return request(article, List.of());
    }

    private static EnrichmentRequest request(
            WikipediaArticle article, List<? extends objectview.Viewable> current) {
        FieldRef schema = FieldRef.described("members", FieldKind.COLLECTION,
                FieldKind.REFERENCE, "List<DeityGroupMember>", true, true,
                "DeityGroupMember", false, false, false, false, "", false);
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
                      <td>judgement</td></tr>
                </table>
                """);
        return new WikipediaArticle(
                wikipedia.WikipediaArticleClient.sourceDocument(
                        "Assessors of Maat", "1", parsed.text(), List.of(), parsed.links()),
                parsed.text(), List.of(), parsed.structuredSections());
    }
}
