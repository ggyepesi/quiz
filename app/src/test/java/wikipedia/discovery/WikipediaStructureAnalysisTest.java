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

    @Test void structuralCardsLeadWithSourceMeaningAndKeepParserPositionSecondary() {
        WikipediaDiscoveryRows.SectionPattern labour =
                new WikipediaDiscoveryRows.SectionPattern(
                        "Slaying the Nemean lion", 1, 1, 1, "3",
                        List.of(new WikipediaDiscoveryRows.SectionExample(
                                "Heracles",
                                "Labours of Heracles › Slaying the Nemean lion",
                                3, List.of(), 40)), List.of());
        WikipediaDiscoveryRows.ListItemContent item =
                new WikipediaDiscoveryRows.ListItemContent(
                        "Heracles", "Labours of Heracles", "Ordered list", 1,
                        new WikipediaArticle.ListItem("Item 1",
                                "Slay the Nemean lion and bring back its skin.",
                                List.of(), List.of(), 10, 58));
        WikipediaDiscoveryRows.SectionContent paragraph =
                new WikipediaDiscoveryRows.SectionContent(
                        "Heracles", "Labours of Heracles",
                        new WikipediaArticle.Block("Paragraph 3",
                                WikipediaArticle.Block.Kind.PARAGRAPH,
                                "Heracles completed twelve labours for Eurystheus.",
                                List.of(), 60, 110));

        assertEquals("Labours of Heracles › Slaying the Nemean lion",
                labour.getDisplayName());
        assertEquals("Slay the Nemean lion and bring back its skin.",
                item.getDisplayName());
        assertEquals("Item 1", item.sourcePosition());
        assertEquals("Heracles completed twelve labours for Eurystheus.",
                paragraph.getDisplayName());
        assertEquals("Paragraph 3", paragraph.sourcePosition());
    }

    /** Whether a title was authored is stored on the block (a definition term, a table
     *  caption), not guessed from wording: an authored title that reads like a parser
     *  position stays the title, and an untitled block is captioned by its text. */
    @Test void anAuthoredTitleIsKeptEvenWhenItReadsLikeAParserPosition() {
        WikipediaDiscoveryRows.SectionContent definition =
                new WikipediaDiscoveryRows.SectionContent(
                        "Heracles", "Labours of Heracles",
                        new WikipediaArticle.Block("Table 1",
                                WikipediaArticle.Block.Kind.DEFINITION,
                                "Table 1 The first of the labours.", List.of(), 0, 34));
        WikipediaDiscoveryRows.SectionContent captioned =
                new WikipediaDiscoveryRows.SectionContent(
                        "Heracles", "Labours of Heracles",
                        new WikipediaArticle.Block("Row 2",
                                WikipediaArticle.Block.Kind.TABLE, "", "Row 2 A | B",
                                List.of(), null,
                                new WikipediaArticle.ArticleTable("Row 2", "Row 2",
                                        List.of(), List.of(), 40, 51), 40, 51));
        WikipediaDiscoveryRows.SectionContent untitledTable =
                new WikipediaDiscoveryRows.SectionContent(
                        "Heracles", "Labours of Heracles",
                        new WikipediaArticle.Block("Table 4",
                                WikipediaArticle.Block.Kind.TABLE, "", "A | B",
                                List.of(), null,
                                new WikipediaArticle.ArticleTable("Table", "",
                                        List.of(), List.of(), 60, 65), 60, 65));

        assertEquals("Table 1", definition.getDisplayName());
        assertEquals("Row 2", captioned.getDisplayName());
        assertEquals("A | B", untitledTable.getDisplayName());
    }

    @Test void identicalHeadingsUnderDifferentParentsRemainDifferentStructures() {
        WikipediaArticle.Section first = new WikipediaArticle.Section(
                "Traditions", 2, List.of(),
                List.of(new WikipediaArticle.Section(
                        "First labour", 3, List.of(), List.of(), 10, 20)), 0, 20);
        WikipediaArticle.Section second = new WikipediaArticle.Section(
                "Labours of Heracles", 2, List.of(),
                List.of(new WikipediaArticle.Section(
                        "First labour", 3, List.of(), List.of(), 30, 40)), 21, 40);
        WikipediaDiscoveryQuery.Article source = new WikipediaDiscoveryQuery.Article(
                instance("Q122248", "Heracles", "Heracles"), "Heracles",
                article("Heracles", List.of(first, second), List.of()), null, "");

        List<WikipediaDiscoveryRows.SectionPattern> firstLabours =
                WikipediaStructureAnalysis.analyze(List.of(source)).sections().stream()
                        .filter(pattern -> pattern.heading().equals("First labour"))
                        .toList();

        assertEquals(List.of(
                        "Labours of Heracles › First labour",
                        "Traditions › First labour"),
                firstLabours.stream().map(Viewable::getDisplayName).sorted().toList());
    }

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
        assertEquals("A giant heron may have been the animal model for Bennu.",
                result.contents().getFirst().title());
        assertEquals("Paragraph 1", result.contents().getFirst().sourcePosition());
        assertEquals("Possible animal model", result.contents().getFirst().sectionPath());
        assertEquals("A giant heron may have been the animal model for Bennu.",
                result.contents().getFirst().text());
        assertEquals("Bennu heron", result.links().getFirst().targetTitle());
        assertEquals("Possible animal model", result.links().getFirst().section());
        assertEquals("Infobox deity.symbol", result.infoboxes().getFirst().key());
        assertEquals(List.of(source), result.infoboxes().getFirst().members());
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
        assertTrue(result.contents().stream().allMatch(Viewable.class::isInstance));
        assertTrue(result.listItems().stream().allMatch(Viewable.class::isInstance));
        assertTrue(result.tableRows().stream().allMatch(Viewable.class::isInstance));
        assertTrue(result.tableHeaders().stream().allMatch(Viewable.class::isInstance));
        assertTrue(result.categories().stream().allMatch(Viewable.class::isInstance));
        assertTrue(result.links().stream().allMatch(Viewable.class::isInstance));
        assertInstanceOf(Viewable.class,
                result.sections().getFirst().examples().getFirst());
    }

    @Test void aNamedDefinitionIsADirectlySearchableSectionContentCard() {
        WikipediaArticle article = article("Heracles", List.of(new WikipediaArticle.Section(
                "Labours of Heracles", 3,
                List.of(new WikipediaArticle.Block("1. Slay the Nemean Lion",
                        WikipediaArticle.Block.Kind.DEFINITION, "dl > dt + dd",
                        "1. Slay the Nemean Lion Heracles defeated the lion.",
                        List.of(), 10, 68)), List.of(), 0, 68)), List.of());
        WikipediaDiscoveryQuery.Article heracles = new WikipediaDiscoveryQuery.Article(
                instance("Q122248", "Heracles", "Heracles"), "Heracles",
                article, null, "");

        WikipediaDiscoveryRows.SectionContent content =
                WikipediaStructureAnalysis.analyze(List.of(heracles))
                        .contents().getFirst();

        assertEquals("1. Slay the Nemean Lion", content.getDisplayName());
        assertEquals("Labours of Heracles", content.sectionPath());
        assertEquals(WikipediaArticle.Block.Kind.DEFINITION, content.kind());
        assertTrue(content.text().contains("Heracles defeated the lion"));
    }

    @Test void listItemsAndTableRowsAreSearchableProjectionsOfRetainedStructure() {
        var parsed = wikipedia.WikipediaArticleClient.parseArticleText("""
                <h2>Works</h2>
                <ul><li>First<ul><li>Nested</li></ul></li><li>Second</li></ul>
                <table><caption>People</caption><tr><th>Name</th><td>Heracles</td></tr></table>
                """);
        WikipediaArticle source = new WikipediaArticle(
                wikipedia.WikipediaArticleClient.sourceDocument(
                        "Heracles", "1", parsed.text(), List.of(), parsed.links()),
                parsed.text(), List.of(), parsed.structuredSections());
        WikipediaDiscoveryQuery.Article article = new WikipediaDiscoveryQuery.Article(
                instance("Q122248", "Heracles", "Heracles"), "Heracles",
                source, null, "");

        WikipediaStructureAnalysis.Result result =
                WikipediaStructureAnalysis.analyze(List.of(article));

        assertEquals(List.of(1, 2, 1),
                result.listItems().stream()
                        .map(WikipediaDiscoveryRows.ListItemContent::depth).toList());
        assertTrue(result.listItems().stream()
                .allMatch(item -> item.members().equals(List.of(article.instance()))));
        assertEquals("Nested", result.listItems().get(1).content().value());
        assertEquals(1, result.tableRows().size());
        assertEquals(List.of("Name", "Heracles"), result.tableRows().getFirst()
                .content().cells().stream().map(WikipediaArticle.TableCell::value).toList());
        assertEquals(List.of("Name"), result.tableHeaders().stream()
                .map(WikipediaDiscoveryRows.TableHeaderPattern::header).toList());
        assertEquals(List.of(article.instance()), result.tableHeaders().getFirst().members());
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

    /** HTML element outcomes are compared across fetched articles, with coverage over the selected
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
