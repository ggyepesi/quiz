package datasource.wikipedia;

import datasource.evidence.WikipediaArticle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WikipediaTableColumnLinksTest {

    @Test void selectsOnlyLinksFromTheConfiguredStructuralColumnIncludingSpans() {
        var parsed = wikipedia.WikipediaArticleClient.parseArticleText("""
                <h2>Description</h2>
                <h3>List of names</h3>
                <table><caption>Assessors</caption>
                  <tr><th rowspan='2'>Number</th><th colspan='2'>Identity</th></tr>
                  <tr><th>Name of the deity</th><th>Task</th></tr>
                  <tr><td>1</td><td><a href='/wiki/Ra'>Ra</a></td>
                      <td><a href='/wiki/Maat'>truth</a></td></tr>
                  <tr><td>2</td><td><a href='/wiki/Thoth'>Thoth</a></td>
                      <td>judgement</td></tr>
                </table>
                """);
        WikipediaArticle article = new WikipediaArticle(
                wikipedia.WikipediaArticleClient.sourceDocument(
                        "Assessors of Maat", "1", parsed.text(), List.of(),
                        parsed.links()),
                parsed.text(), List.of(), parsed.structuredSections());
        var spec = new WikipediaDatasourceProvider.ArticleTableColumnLinkSpec(
                "Description > List of names", "Assessors", "Name of the deity");

        WikipediaTableColumnLinks.Selection result =
                WikipediaTableColumnLinks.select(article, spec);

        assertEquals(1, result.matchingSections());
        assertEquals(1, result.matchingTables());
        assertEquals(2, result.matchingRows());
        assertEquals(List.of("Ra", "Thoth"), result.links().stream()
                .map(value -> value.link().targetTitle()).toList(),
                "the Task-column link is not member evidence");
    }

    @Test void aDifferentSectionPathDoesNotFallBackToMatchingTextElsewhere() {
        var parsed = wikipedia.WikipediaArticleClient.parseArticleText("""
                <h2>Other section</h2><table><tr><th>Name</th></tr>
                <tr><td><a href='/wiki/Ra'>Ra</a></td></tr></table>
                """);
        WikipediaArticle article = new WikipediaArticle(
                wikipedia.WikipediaArticleClient.sourceDocument(
                        "Article", "1", parsed.text(), List.of(), parsed.links()),
                parsed.text(), List.of(), parsed.structuredSections());

        var result = WikipediaTableColumnLinks.select(article,
                new WikipediaDatasourceProvider.ArticleTableColumnLinkSpec(
                        "Description", "", "Name"));

        assertEquals(List.of(), result.links());
        assertEquals(0, result.matchingSections());
    }
}
