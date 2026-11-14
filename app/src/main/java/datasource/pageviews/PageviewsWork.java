package datasource.pageviews;

import batch.WorkDescriptor;
import batch.WorkUnit;

import java.util.List;
import java.util.Map;

/** The one executable unit used by both Pageviews inspection and generation. */
public final class PageviewsWork {
    private PageviewsWork() { }

    public static WorkUnit<Long> article(
            String title, PageviewsClient.Window window, PageviewsClient client) {
        return new WorkUnit<>() {
            @Override public WorkDescriptor descriptor() {
                return new WorkDescriptor("wikimedia-pageviews",
                        "pageviews:" + window.first() + ":" + window.last() + ":" + title,
                        "Views of " + title, Map.of("title", title));
            }
            @Override public String request() { return PageviewsClient.url(title, window); }
            @Override public Long execute() throws Exception { return client.views(title, window); }
            @Override public List<? extends WorkUnit<Long>> split() { return List.of(); }
        };
    }
}
