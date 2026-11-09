package wikipedia.discovery;

import datasource.evidence.InfoboxParameters;
import datasource.evidence.WikipediaArticle;
import quiz.transform.discovery.WikipediaArticleFetchQuery;
import wikidata.explore.extract.WikidataDynamicObject;
import work.Query;
import work.QueryContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the two existing structured views of each explicitly selected Wikipedia page.
 * The article client owns sections/blocks/links/categories and the infobox parser owns
 * native parameters/rendered rows; both read one parse response, so one request and one
 * revision per article. This query only keeps them together for comparison.
 */
public final class WikipediaDiscoveryQuery
        implements Query<List<WikipediaDiscoveryQuery.Article>> {
    public static final int MAX_ARTICLES = 50;

    private final List<WikidataDynamicObject> instances;
    private final wikipedia.WikipediaArticleClient articles;

    public WikipediaDiscoveryQuery(List<WikidataDynamicObject> instances) {
        this(instances, new wikipedia.WikipediaArticleClient());
    }

    WikipediaDiscoveryQuery(List<WikidataDynamicObject> instances,
            wikipedia.WikipediaArticleClient articles) {
        this.instances = selected(instances);
        this.articles = java.util.Objects.requireNonNull(articles);
    }

    @Override public String purpose() { return "Inspect selected Wikipedia articles"; }

    @Override public String skeleton() {
        return "selected instances -> one parse per article (structure + infobox) -> comparison";
    }

    @Override public String queryType() { return "Wikipedia API"; }

    @Override public String description() {
        return "Read exactly the selected English Wikipedia pages without inferring fields";
    }

    @Override public Map<String, String> parameters() {
        return Map.of("selectedArticles", Integer.toString(instances.size()),
                "maximum", Integer.toString(MAX_ARTICLES));
    }

    @Override public List<Article> execute(QueryContext context) throws Exception {
        List<WikipediaArticleFetchQuery.Result> fetched =
                new WikipediaArticleFetchQuery(instances, articles).execute(context);
        List<Article> result = new ArrayList<>();
        for (WikipediaArticleFetchQuery.Result value : fetched) {
            result.add(new Article(value.instance(), value.title(), value.article(),
                    value.available() ? value.infobox() : null,
                    value.available() ? value.elements() : null, value.problem()));
        }
        return List.copyOf(result);
    }

    @Override public int rowCount(List<Article> result) {
        return result == null ? 0 : result.size();
    }

    @Override public String summary(List<Article> result) {
        List<Article> values = result == null ? List.of() : result;
        long articles = values.stream().filter(Article::available).count();
        long infoboxes = values.stream().filter(value -> value.infobox() != null).count();
        return "Fetched " + articles + " of " + values.size()
                + " selected structured article(s) and " + infoboxes + " infobox(es).";
    }

    /** Builds the same comparison input from evidence already retained in a snapshot. */
    public static List<Article> retained(List<WikidataDynamicObject> instances) {
        return selected(instances).stream()
                .map(value -> new Article(value, value.enwikiTitle(),
                        value.wikipediaArticle(), value.infoboxParameters(),
                        value.wikipediaArticle() == null
                                ? "No structured Wikipedia article is retained." : ""))
                .toList();
    }

    private static List<WikidataDynamicObject> selected(
            List<WikidataDynamicObject> instances) {
        List<WikidataDynamicObject> values = instances == null ? List.of()
                : instances.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (values.size() > MAX_ARTICLES) {
            throw new IllegalArgumentException("Wikipedia discovery accepts at most "
                    + MAX_ARTICLES + " explicitly selected articles; " + values.size()
                    + " were selected.");
        }
        return values;
    }

    /**
     * {@code elements} is the block-level element inventory of the fetched HTML, or null
     * when this article was not fetched in this run: a retained article keeps only the
     * blocks its structure chose, so it cannot say what was dropped.
     */
    public record Article(
            WikidataDynamicObject instance,
            String title,
            WikipediaArticle article,
            InfoboxParameters infobox,
            List<wikipedia.ArticleElementInventory.ElementKind> elements,
            String problem) {
        public Article(WikidataDynamicObject instance, String title,
                       WikipediaArticle article, InfoboxParameters infobox, String problem) {
            this(instance, title, article, infobox, null, problem);
        }

        public Article {
            elements = elements == null ? null : List.copyOf(elements);
            instance = java.util.Objects.requireNonNull(instance);
            title = title == null ? "" : title.trim();
            problem = problem == null ? "" : problem.trim();
        }
        public boolean available() { return article != null; }
    }
}
