package quiz.transform.discovery;

import datasource.evidence.WikipediaArticle;
import wikidata.explore.extract.WikidataDynamicObject;
import work.Query;
import work.QueryContext;

import java.util.List;
import java.util.Map;

/** Explicit, bounded acquisition of the structured articles for confirmed instances. */
public final class WikipediaArticleFetchQuery
        implements Query<List<WikipediaArticleFetchQuery.Result>> {
    private final List<WikidataDynamicObject> instances;
    private final wikipedia.WikipediaArticleClient articles;

    public WikipediaArticleFetchQuery(List<WikidataDynamicObject> instances) {
        this(instances, new wikipedia.WikipediaArticleClient());
    }

    public WikipediaArticleFetchQuery(List<WikidataDynamicObject> instances,
            wikipedia.WikipediaArticleClient articles) {
        this.instances = instances == null ? List.of() : instances.stream()
                .filter(java.util.Objects::nonNull).distinct().toList();
        this.articles = java.util.Objects.requireNonNull(articles);
    }

    @Override public String purpose() { return "Fetch structured Wikipedia articles"; }

    @Override public String skeleton() {
        return "Read one versioned English Wikipedia article per selected instance; "
                + "retain headings, paragraphs, lists, tables, links and categories";
    }

    @Override public String queryType() { return "Wikipedia API"; }

    @Override public String description() {
        return "Acquire inspectable source documents without inferring domain values";
    }

    @Override public Map<String, String> parameters() {
        return Map.of("articles", Integer.toString(instances.size()));
    }

    @Override public List<Result> execute(QueryContext context) throws Exception {
        java.util.ArrayList<Result> results = new java.util.ArrayList<>();
        for (WikidataDynamicObject instance : instances) {
            String title = instance.enwikiTitle();
            if (title.isBlank()) {
                results.add(new Result(instance, "", null,
                        "No English Wikipedia article is retained for this instance."));
                continue;
            }
            try {
                wikipedia.WikipediaArticleClient.StructuredPage acquired =
                        articles.structuredByTitle(title).execute(context);
                if (acquired == null) {
                    results.add(new Result(instance, title, null,
                            "Wikipedia did not return this article."));
                } else {
                    results.add(new Result(instance, title,
                            acquired.article().structuredArticle(), acquired.infobox(),
                            acquired.elements(), ""));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (java.util.concurrent.CancellationException cancelled) {
                throw cancelled;
            } catch (Exception failure) {
                String message = failure.getMessage();
                results.add(new Result(instance, title, null,
                        message == null || message.isBlank()
                                ? failure.getClass().getSimpleName() : message));
            }
        }
        return List.copyOf(results);
    }

    @Override public int rowCount(List<Result> result) {
        return result == null ? 0 : result.size();
    }

    @Override public String summary(List<Result> result) {
        List<Result> values = result == null ? List.of() : result;
        long acquired = values.stream().filter(Result::available).count();
        return "Fetched " + acquired + " of " + values.size()
                + " selected Wikipedia article(s); " + (values.size() - acquired)
                + " unavailable.";
    }

    /** {@code infobox} and {@code elements} come from the same parse response, so the same
     *  revision, as {@code article}; the infobox is null when the page has none. */
    public record Result(WikidataDynamicObject instance, String title,
                         WikipediaArticle article,
                         datasource.evidence.InfoboxParameters infobox,
                         List<wikipedia.ArticleElementInventory.ElementKind> elements,
                         String problem) {
        public Result(WikidataDynamicObject instance, String title,
                      WikipediaArticle article, String problem) {
            this(instance, title, article, null, List.of(), problem);
        }

        public Result {
            elements = elements == null ? List.of() : List.copyOf(elements);
            instance = java.util.Objects.requireNonNull(instance);
            title = title == null ? "" : title.trim();
            problem = problem == null ? "" : problem.trim();
        }
        public boolean available() { return article != null; }
    }
}
