package pageviews.discovery;

import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.annotations.Hidden;
import objectview.annotations.Label;
import objectview.annotations.Link;
import objectview.annotations.Reference;
import wikidata.explore.extract.WikidataDynamicObject;

/** The views of one selected instance's English Wikipedia article over one window. */
public final class ArticleViews extends ViewableAdapter {
    @Reference @Label("Selected instance")
    private final WikidataDynamicObject instance;
    @DisplayField @Label("Article")
    private final String article;
    /** Null when the service holds no record; never zero for "unknown". */
    @Label("Views")
    private final Long views;
    @Label("Window")
    private final String window;
    @Label("Outcome")
    private final String outcome;
    @Hidden
    private final boolean failed;
    @Link @Label("Pageviews request")
    private final String request;

    public ArticleViews(WikidataDynamicObject instance, String article, Long views,
            String window, String outcome, boolean failed, String request) {
        this.instance = instance;
        this.article = article == null ? "" : article;
        this.views = views;
        this.window = window == null ? "" : window;
        this.outcome = outcome == null ? "" : outcome;
        this.failed = failed;
        this.request = request == null ? "" : request;
    }

    public WikidataDynamicObject instance() { return instance; }
    public String article() { return article; }
    public Long views() { return views; }
    public String outcome() { return outcome; }
    public boolean failed() { return failed; }

    @Override public String getIdentifier() {
        return instance == null ? article : instance.typeKey() + ":" + instance.qid();
    }
    @Override public String getDisplayName() { return article; }
}
