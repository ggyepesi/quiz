package wikipedia.discovery;

import datasource.evidence.InfoboxParameters;
import datasource.evidence.WikipediaArticle;
import objectview.Viewable;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The comparison keeps source structure and coverage; it never invents a domain fact. */
class WikipediaStructureAnalysisTest {

    @Test void aPossibleAnimalModelSectionKeepsItsExactArticleEvidence() {
        WikipediaArticle bennu = article("Bennu", List.of(new WikipediaArticle.Section(
                "Possible animal model", 2,
                List.of(new WikipediaArticle.Block("Paragraph 1",
                        WikipediaArticle.Block.Kind.PARAGRAPH,
                        "A giant heron may have been the animal model for Bennu.",
                        List.of(new WikipediaArticle.ArticleLink(
                                "Bennu heron", "Bennu heron", 2, 13)), 0, 58)),
                List.of(), 0, 58)), List.of("Egyptian gods"));
        WikidataDynamicObject source = instance("Q146083", "Bennu", "Bennu");
        WikipediaDiscoveryQuery.Article available = new WikipediaDiscoveryQuery.Article(
                source, "Bennu", bennu,
                InfoboxParameters.fromEnglishWikipedia("Infobox deity",
                        Map.of("symbol", "Grey heron"), "Bennu", "1"), "");
        WikipediaDiscoveryQuery.Article missing = new WikipediaDiscoveryQuery.Article(
                instance("Q2", "Other", "Other"), "Other", null, null,
                "Wikipedia did not return this article.");

        WikipediaStructureAnalysis.Result result =
                WikipediaStructureAnalysis.analyze(List.of(available, missing));

        assertEquals(2, result.selectedArticles());
        assertEquals(1, result.availableArticles());
        assertEquals("Possible animal model", result.sections().getFirst().heading());
        assertEquals(1, result.sections().getFirst().articles());
        assertEquals("Bennu", result.sections().getFirst().examples().getFirst().title());
        assertEquals("A giant heron may have been the animal model for Bennu.",
                result.sections().getFirst().examples().getFirst().blocks()
                        .getFirst().value());
        assertEquals("Bennu heron", result.links().getFirst().targetTitle());
        assertEquals("Possible animal model", result.links().getFirst().section());
        assertEquals("Infobox deity.symbol", result.infoboxes().getFirst().key());
        assertEquals(1, result.unavailable().size());
    }

    @Test void equalHeadingsAcrossArticlesBecomeOneCoverageRow() {
        WikipediaDiscoveryQuery.Article first = observed("Q1", "One", "Depiction");
        WikipediaDiscoveryQuery.Article second = observed("Q2", "Two", "depiction");

        WikipediaStructureAnalysis.Result result =
                WikipediaStructureAnalysis.analyze(List.of(first, second));

        assertEquals(1, result.sections().size());
        assertEquals(2, result.sections().getFirst().articles());
        assertEquals(2, result.sections().getFirst().selectedArticles());
        assertEquals(2, result.sections().getFirst().examples().size());
    }

    @Test void infoboxExamplesExposeStructuredValuesToObjectView() {
        var value = new datasource.evidence.WikipediaFragment("Ra and Hathor",
                List.of(new WikipediaArticle.ArticleLink("Ra", "Ra", 0, 2),
                        new WikipediaArticle.ArticleLink("Hathor", "Hathor", 7, 13)),
                List.of());
        var infobox = InfoboxParameters.fromEnglishWikipediaStructured("Infobox deity",
                Map.of(), Map.of("Parents", value), "Maat", "1");
        WikipediaDiscoveryQuery.Article maat = new WikipediaDiscoveryQuery.Article(
                instance("Q170471", "Maat", "Maat"), "Maat",
                article("Maat", List.of(), List.of()), infobox, "");

        var example = WikipediaStructureAnalysis.analyze(List.of(maat))
                .infoboxes().getFirst().examples().getFirst();

        assertInstanceOf(Viewable.class, example.value());
        assertEquals("Hathor", example.value().links().get(1).targetTitle());
    }

    @Test void everyDiscoveryResultIsAnObjectViewValue() {
        WikipediaStructureAnalysis.Result result =
                WikipediaStructureAnalysis.analyze(List.of(observed(
                        "Q1", "Bennu", "Possible animal model")));

        assertTrue(result.articles().stream().allMatch(Viewable.class::isInstance));
        assertTrue(result.sections().stream().allMatch(Viewable.class::isInstance));
        assertTrue(result.categories().stream().allMatch(Viewable.class::isInstance));
        assertTrue(result.links().stream().allMatch(Viewable.class::isInstance));
        assertInstanceOf(Viewable.class,
                result.sections().getFirst().examples().getFirst());
    }

    @Test void retainedAnalysisCannotBypassTheExplicitArticleLimit() {
        List<WikidataDynamicObject> values = java.util.stream.IntStream.rangeClosed(
                        1, WikipediaDiscoveryQuery.MAX_ARTICLES + 1)
                .mapToObj(index -> instance("Q" + index, "Entity " + index,
                        "Article " + index))
                .toList();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> WikipediaDiscoveryQuery.retained(values));

        assertTrue(failure.getMessage().contains("at most "
                + WikipediaDiscoveryQuery.MAX_ARTICLES));
    }

    /** Block kinds are compared across fetched articles, with coverage over the selected
     *  count; a dropped kind shows which articles carry it and its first text. */
    @Test void blockKindsAggregateAcrossFetchedArticles() {
        var dd = new wikipedia.ArticleElementInventory.ElementKind(
                "dd", "Not retained", 3, "Indented commentary.");
        var p = new wikipedia.ArticleElementInventory.ElementKind(
                "p", "PARAGRAPH", 5, "Maat was the goddess of truth.");
        WikipediaDiscoveryQuery.Article maat = withElements(
                observed("Q170471", "Maat", "Worship"), List.of(dd, p));
        WikipediaDiscoveryQuery.Article ra = withElements(
                observed("Q36289", "Ra", "Worship"), List.of(p));
        WikipediaDiscoveryQuery.Article missing = new WikipediaDiscoveryQuery.Article(
                instance("Q2", "Other", "Other"), "Other", null, null, null,
                "Wikipedia did not return this article.");

        WikipediaStructureAnalysis.Result result =
                WikipediaStructureAnalysis.analyze(List.of(maat, ra, missing));

        assertTrue(result.blockKindsAvailable());
        var paragraphs = result.blockKinds().getFirst();
        assertEquals("p", paragraphs.element());
        assertEquals(2, paragraphs.articles());
        assertEquals(10, paragraphs.occurrences());
        var dropped = result.blockKinds().get(1);
        assertEquals("Not retained", dropped.retainedAs());
        assertEquals(1, dropped.articles());
        assertEquals(3, dropped.selectedArticles(),
                "coverage counts every selected article, the unavailable one included");
        assertEquals("Indented commentary.", dropped.examples().getFirst().text());
    }

    /** A retained article keeps only the blocks its structure chose, so it cannot say
     *  what was dropped: the analysis reports the inventory as unavailable, not empty. */
    @Test void retainedArticlesHaveNoBlockKindInventory() {
        WikipediaStructureAnalysis.Result result = WikipediaStructureAnalysis.analyze(
                List.of(observed("Q170471", "Maat", "Worship")));

        assertTrue(!result.blockKindsAvailable());
        assertTrue(result.blockKinds().isEmpty());
    }

    private static WikipediaDiscoveryQuery.Article withElements(
            WikipediaDiscoveryQuery.Article value,
            List<wikipedia.ArticleElementInventory.ElementKind> elements) {
        return new WikipediaDiscoveryQuery.Article(value.instance(), value.title(),
                value.article(), value.infobox(), elements, value.problem());
    }

    private static WikipediaDiscoveryQuery.Article observed(
            String qid, String title, String heading) {
        WikipediaArticle article = article(title,
                List.of(new WikipediaArticle.Section(heading, 2,
                        List.of(new WikipediaArticle.Block("Paragraph 1",
                                WikipediaArticle.Block.Kind.PARAGRAPH,
                                "Example text", List.of(), 0, 12)),
                        List.of(), 0, 12)), List.of("Examples"));
        return new WikipediaDiscoveryQuery.Article(instance(qid, title, title),
                title, article, null, "");
    }

    private static WikidataDynamicObject instance(String qid, String name, String title) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, name);
        value.enwikiTitle(title);
        return value;
    }

    private static WikipediaArticle article(String title,
            List<WikipediaArticle.Section> sections, List<String> categories) {
        return new WikipediaArticle(
                wikipedia.WikipediaArticleClient.sourceDocument(
                        title, "1", "Example text", categories),
                "Example text", categories, sections);
    }
}
