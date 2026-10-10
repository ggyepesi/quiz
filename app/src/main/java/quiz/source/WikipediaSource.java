package quiz.source;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import objectview.ViewableAdapter;
import objectview.annotations.Hidden;
import objectview.annotations.Link;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** The English Wikipedia article corresponding to one modeled source entity. */
public final class WikipediaSource extends ViewableAdapter implements Source {
    @Hidden private final String title;
    @Hidden private final String url;
    @Link private final String article;

    @JsonCreator
    public WikipediaSource(@JsonProperty("title") String title) {
        this.title = title == null ? "" : title.trim();
        this.url = this.title.isBlank() ? "" : "https://en.wikipedia.org/wiki/"
                + URLEncoder.encode(this.title.replace(' ', '_'), StandardCharsets.UTF_8)
                        .replace("+", "%20");
        this.article = url.isBlank() ? "" : this.title + "|" + url;
    }

    @Override public String provenance() { return "wikipedia"; }
    @Override public String id() { return "enwiki:" + title; }
    @Override public String getIdentifier() { return id(); }
    @Override public String getDisplayName() { return title; }
    public String title() { return title; }
    public String url() { return url; }

    @Override public boolean equals(Object value) {
        return value instanceof WikipediaSource other && title.equals(other.title);
    }

    @Override public int hashCode() { return title.hashCode(); }
}
