package datasource.wikipedia;

import datasource.api.SourceRecipe;
import datasource.api.acquisition.AcquiredPopulation;
import org.junit.jupiter.api.Test;
import wikidata.explore.wikiproject.WikiProjectArticle;
import wikidata.explore.wikiproject.WikiProjectCategoryReader;
import wikidata.explore.wikiproject.WikiProjectMediaWikiClient;
import work.QueryContext;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A category population admits only the article members carrying real Wikidata QIDs. */
class WikipediaCategoryPopulationOperationTest {

    @Test void unresolvedArticlesAreReportedAndNeverInventedAsMembers() throws Exception {
        WikiProjectArticle ennead = article("Ennead", "Q233458");
        WikiProjectArticle unresolved = article("A page without Wikidata", "");
        WikiProjectCategoryReader reader = new WikiProjectCategoryReader(
                new WikiProjectMediaWikiClient("test", 0) {
                    @Override public String get(String queryString) {
                        throw new AssertionError("the injected reader supplies the result");
                    }
                }) {
            @Override public CategoryMemberPage categoryMemberPage(
                    String category, int limit, int namespace, String continuation) {
                assertEquals("Category:Groups of Egyptian deities", category);
                assertEquals(100, limit);
                assertEquals(0, namespace);
                assertEquals("", continuation);
                return new CategoryMemberPage(List.of(ennead, unresolved), "");
            }

            @Override public String categoryMemberRequestUrl(
                    String category, int limit, int namespace, String continuation) {
                return "https://example.test/category?page=1";
            }
        };
        WikipediaCategoryPopulationOperation operation =
                new WikipediaCategoryPopulationOperation(() -> reader);

        AcquiredPopulation result = operation.acquisition(recipe())
                .query().execute(new QueryContext());

        assertEquals(List.of("Q233458"), result.members().stream()
                .map(datasource.EntityRef::id).toList());
        assertEquals(List.of("A page without Wikidata"),
                result.unresolvedSourceRecords());
    }

    @Test void theArticleLimitIsAnExplicitHardBound() {
        assertThrows(IllegalArgumentException.class, () ->
                WikipediaCategoryPopulationOperation.limit(new SourceRecipe(
                        "wikipedia", "category-members",
                        Map.of("category", "Groups", "limit", "0"))));
        assertThrows(IllegalArgumentException.class, () ->
                WikipediaCategoryPopulationOperation.limit(new SourceRecipe(
                        "wikipedia", "category-members",
                        Map.of("category", "Groups", "limit", "unbounded"))));
    }

    @Test void everyContinuationPageIsAVisibleChildRequest() throws Exception {
        List<String> continuations = new ArrayList<>();
        WikiProjectCategoryReader reader = readerWithPages(continuations, null);
        WikipediaCategoryPopulationOperation operation =
                new WikipediaCategoryPopulationOperation(() -> reader);
        work.LogNode root = new work.LogNode(work.LogKind.WORKFLOW, "Generate");
        work.WorkflowRecorder recorder = new work.WorkflowRecorder(root);
        recorder.start();

        AcquiredPopulation result = operation.acquisition(recipe())
                .query().execute(new QueryContext().withRecorder(recorder));

        assertEquals(List.of("", "next"), continuations);
        assertEquals(List.of("Q1", "Q2"), result.members().stream()
                .map(datasource.EntityRef::id).toList());
        work.LogNode category = root.steps().iterator().next();
        assertEquals(2, category.steps().size());
        assertEquals(List.of(
                        "https://example.test/category?page=1",
                        "https://example.test/category?page=2"),
                category.steps().stream().map(work.LogNode::request).toList());
        assertTrue(category.summary().contains("2 page(s)"), category.summary());
    }

    @Test void aRetryIsOwnedByTheVisiblePageWork() throws Exception {
        List<String> continuations = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        WikiProjectCategoryReader reader = readerWithPages(continuations, calls);
        WikipediaCategoryPopulationOperation operation =
                new WikipediaCategoryPopulationOperation(() -> reader);
        work.LogNode root = new work.LogNode(work.LogKind.WORKFLOW, "Generate");
        work.WorkflowRecorder recorder = new work.WorkflowRecorder(root);
        recorder.start();

        AcquiredPopulation result = operation.acquisition(recipe())
                .query().execute(new QueryContext().withRecorder(recorder));

        assertEquals(3, calls.get(), "first page retries once, then page two runs");
        assertEquals(2, result.members().size());
        work.LogNode firstPage = root.steps().iterator().next()
                .steps().iterator().next();
        assertTrue(firstPage.summary().contains("Attempt 1/2 failed"),
                firstPage.summary());
        assertTrue(firstPage.summary().contains("ok (attempt 2)"),
                firstPage.summary());
    }

    private static WikiProjectCategoryReader readerWithPages(
            List<String> continuations, AtomicInteger calls) {
        return new WikiProjectCategoryReader(
                new WikiProjectMediaWikiClient("test", 0)) {
            @Override public CategoryMemberPage categoryMemberPage(
                    String category, int limit, int namespace, String continuation)
                    throws Exception {
                continuations.add(continuation);
                int call = calls == null ? 0 : calls.incrementAndGet();
                if (calls != null && call == 1) {
                    throw new objectview.utils.UrlOpener.HttpStatusException(
                            503, new java.net.URL(
                                    "https://example.test/category?page=1"));
                }
                return continuation.isBlank()
                        ? new CategoryMemberPage(List.of(article("First", "Q1")), "next")
                        : new CategoryMemberPage(List.of(article("Second", "Q2")), "");
            }

            @Override public String categoryMemberRequestUrl(
                    String category, int limit, int namespace, String continuation) {
                return "https://example.test/category?page="
                        + (continuation.isBlank() ? "1" : "2");
            }
        };
    }

    private static SourceRecipe recipe() {
        return new SourceRecipe("wikipedia", "category-members", Map.of(
                "category", "Groups of Egyptian deities", "limit", "100"));
    }

    private static WikiProjectArticle article(String title, String qid) {
        WikiProjectArticle article = new WikiProjectArticle(
                title, title, 1, "Category:Groups of Egyptian deities");
        article.qid(qid);
        return article;
    }
}
