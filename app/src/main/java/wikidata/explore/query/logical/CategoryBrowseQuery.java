package wikidata.explore.query.logical;

import work.Query;
import work.QueryContext;
import wikidata.explore.wikiproject.WikiProjectArticle;
import wikidata.explore.wikiproject.WikiProjectCategoryReader;
import wikidata.explore.wikiproject.WikiProjectMediaWikiClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One lazy level of a Wikipedia category: parents, children and article members. */
public final class CategoryBrowseQuery implements Query<CategoryBrowseQuery.Result> {
    private final String category;
    private final int articleLimit;
    private final int categoryLimit;

    public CategoryBrowseQuery(String category, int articleLimit) {
        String value = category == null ? "" : category.trim();
        this.category = value.regionMatches(true, 0, "Category:", 0, 9)
                ? value : "Category:" + value;
        this.articleLimit = Math.max(1, articleLimit);
        this.categoryLimit = Math.max(50, Math.min(500, articleLimit));
    }

    @Override public String purpose() { return "Browse Wikipedia category"; }
    @Override public String skeleton() {
        return "parents + subcategories + article members with Wikidata QIDs";
    }
    @Override public String queryType() { return "MediaWiki API"; }
    @Override public Map<String, String> parameters() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("category", category);
        out.put("article limit", String.valueOf(articleLimit));
        return out;
    }

    @Override public Result execute(QueryContext context) throws Exception {
        return context.step(category, "MediaWiki API", skeleton(), parameters(), step -> {
            WikiProjectCategoryReader reader = new WikiProjectCategoryReader(
                    WikiProjectMediaWikiClient.interactive());
            step.request(reader.firstParentRequestUrl(category, categoryLimit));
            step.request(reader.firstSubcategoryRequestUrl(category, categoryLimit));
            step.request(reader.firstRequestUrl(category, articleLimit, 0));

            // The three reads are independent, so one click costs the slowest of them
            // rather than their sum. The client paces them against each other.
            List<WikiProjectArticle> parents;
            List<WikiProjectArticle> children;
            List<WikiProjectArticle> articles;
            try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var parentsTask = pool.submit(() ->
                        reader.parentCategories(category, categoryLimit));
                var childrenTask = pool.submit(() ->
                        reader.subcategories(category, categoryLimit));
                var articlesTask = pool.submit(() ->
                        reader.categoryMembers(category, articleLimit, 0));
                parents = unwrap(parentsTask);
                children = unwrap(childrenTask);
                articles = unwrap(articlesTask);
            }
            long resolved = java.util.stream.Stream.of(parents, children, articles)
                    .flatMap(List::stream).filter(article -> article.qid() != null
                    && wikidata.WikidataIds.isQid(article.qid())).count();
            step.summary(parents.size() + " parent(s), " + children.size()
                    + " subcategor" + (children.size() == 1 ? "y" : "ies") + ", "
                    + articles.size() + " article(s), " + resolved + " QID(s)");
            return new Result(category, parents, children, articles);
        });
    }

    // A failing read must surface as the cause the caller would have seen had the
    // three run in sequence, not wrapped in an ExecutionException nobody handles.
    private static <T> T unwrap(java.util.concurrent.Future<T> task) throws Exception {
        try {
            return task.get();
        } catch (java.util.concurrent.ExecutionException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception failure) throw failure;
            if (cause instanceof Error error) throw error;
            throw wrapped;
        }
    }

    @Override public int rowCount(Result result) {
        return result == null ? 0 : result.articles().size();
    }

    @Override public String summary(Result result) {
        return result == null ? "0 articles" : result.articles().size() + " articles";
    }

    public record Result(String category, List<WikiProjectArticle> parents,
                         List<WikiProjectArticle> subcategories,
                         List<WikiProjectArticle> articles) {
        public Result {
            category = category == null ? "" : category;
            parents = parents == null ? List.of() : List.copyOf(parents);
            subcategories = subcategories == null ? List.of() : List.copyOf(subcategories);
            articles = articles == null ? List.of() : List.copyOf(articles);
        }
    }
}
