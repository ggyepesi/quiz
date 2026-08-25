package wikidata.explore.wikiproject;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikiProjectCategoryReaderTest {

    @Test
    void contentCategoryGetsItsQidFromMediaWikiPageProps() throws Exception {
        FakeClient client = new FakeClient(List.of("""
                {
                  "batchcomplete": true,
                  "query": {"pages": [
                    {"pageid": 274048, "ns": 0,
                     "title": "German revolutions of 1848–1849",
                     "pageprops": {"wikibase_item": "Q3699"}},
                    {"pageid": 999, "ns": 0, "title": "Unmodelled article"}
                  ]}
                }
                """));

        List<WikiProjectArticle> result = new WikiProjectCategoryReader(client)
                .categoryMembers("Category:Revolutions of 1848", 20, 0);

        assertEquals(2, result.size());
        assertEquals("Q3699", result.get(0).qid());
        assertEquals(274048, result.get(0).pageId());
        assertEquals("", result.get(1).qid(),
                "an article with no Wikidata item remains explicitly unresolved");
        assertTrue(client.queries.get(0).contains("generator=categorymembers"));
        assertTrue(client.queries.get(0).contains("prop=pageprops"));
    }

    @Test
    void contentCategoryFollowsGeneratorContinuation() throws Exception {
        FakeClient client = new FakeClient(List.of("""
                {"continue":{"gcmcontinue":"page|next","continue":"gcmcontinue||"},
                 "query":{"pages":[
                   {"pageid":1,"ns":0,"title":"First",
                    "pageprops":{"wikibase_item":"Q1"}}
                 ]}}
                """, """
                {"batchcomplete":true,"query":{"pages":[
                  {"pageid":2,"ns":0,"title":"Second",
                   "pageprops":{"wikibase_item":"Q2"}}
                ]}}
                """));

        List<WikiProjectArticle> result = new WikiProjectCategoryReader(client)
                .categoryMembers("Category:Example", 20, 0);

        assertEquals(List.of("Q1", "Q2"), result.stream()
                .map(WikiProjectArticle::qid).toList());
        assertEquals(2, client.queries.size());
        assertTrue(client.queries.get(1).contains("gcmcontinue=page%7Cnext"));
    }

    @Test
    void contentCategoryPresentsGeneratorPagesInTitleOrder() throws Exception {
        FakeClient client = new FakeClient(List.of("""
                {"batchcomplete":true,"query":{"pages":[
                  {"pageid":3,"ns":0,"title":"Zulu",
                   "pageprops":{"wikibase_item":"Q3"}},
                  {"pageid":1,"ns":0,"title":"Alpha",
                   "pageprops":{"wikibase_item":"Q1"}},
                  {"pageid":2,"ns":0,"title":"middle",
                   "pageprops":{"wikibase_item":"Q2"}}
                ]}}
                """));

        List<WikiProjectArticle> result = new WikiProjectCategoryReader(client)
                .categoryMembers("Category:Example", 20, 0);

        assertEquals(List.of("Alpha", "middle", "Zulu"), result.stream()
                .map(WikiProjectArticle::title).toList());
    }

    @Test
    void readsImmediateParentCategoriesWithContinuation() throws Exception {
        FakeClient client = new FakeClient(List.of("""
                {"continue":{"gclcontinue":"1|Parent B"},"query":{"pages":[
                  {"pageid":10,"ns":14,"title":"Category:Parent A",
                   "pageprops":{"wikibase_item":"Q10"}}]}}
                """, """
                {"batchcomplete":true,"query":{"pages":[
                  {"pageid":11,"ns":14,"title":"Category:Parent B"}]}}
                """));

        List<WikiProjectArticle> result = new WikiProjectCategoryReader(client)
                .parentCategories("Category:Child", 20);

        assertEquals(List.of("Category:Parent A", "Category:Parent B"), result.stream()
                .map(WikiProjectArticle::title).toList());
        assertEquals("Q10", result.get(0).qid());
        assertTrue(client.queries.get(0).contains("generator=categories"));
        assertTrue(client.queries.get(0).contains("prop=pageprops"));
        assertTrue(client.queries.get(1).contains("gclcontinue=1%7CParent+B"));
    }

    @Test
    void readsImmediateSubcategoriesAsSubcategoryMembers() throws Exception {
        FakeClient client = new FakeClient(List.of("""
                {"batchcomplete":"","query":{"pages":[
                  {"pageid":20,"ns":14,"title":"Category:Child A",
                   "pageprops":{"wikibase_item":"Q20"}},
                  {"pageid":21,"ns":14,"title":"Category:Child B"}
                ]}}
                """));

        List<WikiProjectArticle> result = new WikiProjectCategoryReader(client)
                .subcategories("Category:Parent", 20);

        assertEquals(List.of("Category:Child A", "Category:Child B"), result.stream()
                .map(WikiProjectArticle::title).toList());
        assertEquals("Q20", result.get(0).qid());
        assertTrue(client.queries.get(0).contains("gcmnamespace=14"));
        assertTrue(client.queries.get(0).contains("gcmtype=subcat"));
        assertTrue(client.queries.get(0).contains("prop=pageprops"));
    }

    private static final class FakeClient extends WikiProjectMediaWikiClient {
        private final List<String> responses;
        private final List<String> queries = new ArrayList<>();
        private int next;

        private FakeClient(List<String> responses) {
            super("test", 0);
            this.responses = responses;
        }

        @Override public String get(String query) {
            queries.add(query);
            return responses.get(next++);
        }
    }
}
