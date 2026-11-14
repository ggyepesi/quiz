package pageviews.discovery;

import datasource.pageviews.PageviewsClient;
import datasource.pageviews.PageviewsWork;
import wikidata.explore.extract.WikidataDynamicObject;
import work.Query;
import work.QueryContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Reads the English Wikipedia views of exactly the selected instances' retained articles,
 * one logged request per article, through the same client generation uses. Inspection
 * only: nothing is written to the instances or the model.
 */
public final class PageviewsDiscoveryQuery implements Query<List<ArticleViews>> {
    public static final int MAX_ARTICLES = 50;

    private final List<WikidataDynamicObject> instances;
    private final PageviewsClient.Window window;
    private final PageviewsClient client;

    public PageviewsDiscoveryQuery(List<WikidataDynamicObject> instances,
            PageviewsClient.Window window, PageviewsClient client) {
        this.instances = instances == null ? List.of() : instances.stream()
                .filter(java.util.Objects::nonNull)
                .filter(value -> !value.enwikiTitle().isBlank()).distinct().toList();
        this.window = java.util.Objects.requireNonNull(window);
        this.client = java.util.Objects.requireNonNull(client);
    }

    @Override public String purpose() { return "Read Wikipedia pageviews"; }

    @Override public String skeleton() {
        return "selected instances -> retained English Wikipedia article -> monthly user "
                + "views over complete months -> total";
    }

    @Override public String queryType() { return "Wikimedia REST API"; }

    @Override public String description() {
        return "Measure how often the selected instances' articles were read, " + window;
    }

    @Override public Map<String, String> parameters() {
        return Map.of("articles", Integer.toString(instances.size()),
                "window", window.toString());
    }

    @Override public List<ArticleViews> execute(QueryContext context) throws Exception {
        List<ArticleViews> rows = new ArrayList<>();
        for (WikidataDynamicObject instance : instances) {
            if (context.cancellation().isCancelled()) {
                throw new java.util.concurrent.CancellationException(
                        "Pageviews discovery was cancelled after " + rows.size()
                                + " of " + instances.size() + " article(s)");
            }
            String title = instance.enwikiTitle();
            batch.WorkUnit<Long> work = PageviewsWork.article(title, window, client);
            String url = work.request();
            rows.add(context.step("Views of " + title, queryType(), skeleton(),
                    Map.of("article", title, "window", window.toString()), step -> {
                        step.request(url);
                        try {
                            Long views = work.execute();
                            step.summary(views == null ? "no record" : views + " views");
                            return new ArticleViews(instance, title, views,
                                    window.toString(), views == null
                                            ? "No record for this title in the window"
                                            : "Read", false, url);
                        } catch (java.util.concurrent.CancellationException
                                | InterruptedException stopped) {
                            throw stopped;
                        } catch (Exception failure) {
                            String message = failure.getMessage() == null
                                    ? failure.getClass().getSimpleName()
                                    : failure.getMessage();
                            step.partial("failed: " + message);
                            return new ArticleViews(instance, title, null,
                                    window.toString(), "Failed: " + message, true, url);
                        }
                    }));
        }
        rows.sort(Comparator.comparing(ArticleViews::views,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return List.copyOf(rows);
    }

    @Override public int rowCount(List<ArticleViews> result) { return result.size(); }

    @Override public work.QueryStatus status(List<ArticleViews> result) {
        return result.stream().anyMatch(ArticleViews::failed)
                ? work.QueryStatus.PARTIAL : work.QueryStatus.OK;
    }

    @Override public String summary(List<ArticleViews> result) {
        long recorded = result.stream().filter(row -> row.views() != null).count();
        long failed = result.stream().filter(ArticleViews::failed).count();
        long missing = result.size() - recorded - failed;
        return recorded + " article(s) have views; " + missing
                + " have no record; " + failed + " request(s) failed";
    }
}
