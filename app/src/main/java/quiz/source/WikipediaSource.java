package quiz.source;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.annotations.Hidden;
import objectview.annotations.Link;
import objectview.annotations.Inline;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** The English Wikipedia article corresponding to one modeled source entity. */
public final class WikipediaSource extends ViewableAdapter implements Source {
    @Hidden private final String title;
    @Hidden private final String url;
    @DisplayField @Link private final String article;
    @Inline private final datasource.evidence.WikipediaArticle retainedArticle;

    @JsonCreator
    public WikipediaSource(
            @JsonProperty("title") String title,
            @JsonProperty("retainedArticle")
            datasource.evidence.WikipediaArticle retainedArticle) {
        this.title = title == null ? "" : title.trim();
        this.url = this.title.isBlank() ? "" : "https://en.wikipedia.org/wiki/"
                + URLEncoder.encode(this.title.replace(' ', '_'), StandardCharsets.UTF_8)
                        .replace("+", "%20");
        this.article = url.isBlank() ? "" : this.title + "|" + url;
        this.retainedArticle = retainedArticle;
    }

    public WikipediaSource(String title) { this(title, null); }

    @Override public String provenance() { return "wikipedia"; }
    @Override public String id() { return "enwiki:" + title; }
    @Override public String getIdentifier() { return id(); }
    @Override public String getDisplayName() { return title; }
    public String title() { return title; }
    public String url() { return url; }
    public datasource.evidence.WikipediaArticle retainedArticle() {
        return retainedArticle;
    }

    @Override public boolean equals(Object value) {
        return value instanceof WikipediaSource other && title.equals(other.title);
    }

    @Override public int hashCode() { return title.hashCode(); }
}
