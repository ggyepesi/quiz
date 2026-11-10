package datasource.evidence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.annotations.Inline;
import objectview.annotations.Label;
import objectview.media.MediaValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One interpreted Wikipedia HTML fragment before a domain field assigns it meaning.
 *
 * <p>The text is the backwards-compatible scalar projection. Links retain the target that
 * disambiguates equal visible labels, and media retains values that have no useful text at
 * all (hieroglyphs are the forcing example). Article bodies and rendered infobox cells are
 * read by the same fragment parser.</p>
 */
public final class WikipediaFragment extends ViewableAdapter {
    @DisplayField @Label("Text") private final String text;
    @Inline private final List<WikipediaArticle.ArticleLink> links;
    @Inline private final List<Media> media;

    @JsonCreator
    public WikipediaFragment(
            @JsonProperty("text") String text,
            @JsonProperty("links") List<WikipediaArticle.ArticleLink> links,
            @JsonProperty("media") List<Media> media) {
        this.text = text == null ? "" : text.trim();
        this.links = immutable(links);
        this.media = immutable(media);
    }

    public static WikipediaFragment text(String value) {
        return new WikipediaFragment(value, List.of(), List.of());
    }

    public String text() { return text; }
    public List<WikipediaArticle.ArticleLink> links() { return links; }
    public List<Media> media() { return media; }
    public boolean isEmpty() { return text.isBlank() && links.isEmpty() && media.isEmpty(); }

    /** Combines repeated rendered rows without losing either row's structured evidence. */
    public WikipediaFragment append(WikipediaFragment other) {
        if (other == null || other.isEmpty()) return this;
        if (isEmpty()) return other;
        if (sameEvidence(other)) return this;
        String separator = text.isBlank() || other.text.isBlank() ? "" : "; ";
        int offset = text.length() + separator.length();
        List<WikipediaArticle.ArticleLink> combinedLinks = new ArrayList<>(links);
        for (WikipediaArticle.ArticleLink link : other.links) {
            combinedLinks.add(new WikipediaArticle.ArticleLink(link.text(), link.targetTitle(),
                    link.start() + offset, link.end() + offset));
        }
        List<Media> combinedMedia = new ArrayList<>(media);
        combinedMedia.addAll(other.media);
        return new WikipediaFragment(text + separator + other.text,
                combinedLinks, combinedMedia);
    }

    @Override public String getIdentifier() {
        return Integer.toHexString(Objects.hash(text,
                links.stream().map(link -> link.text() + '\0' + link.targetTitle()
                        + '\0' + link.start() + '\0' + link.end()).toList(), media));
    }

    @Override public String getDisplayName() {
        if (!text.isBlank()) return text;
        if (!media.isEmpty()) return media.size() == 1 ? "Media" : "Media (" + media.size() + ')';
        return "Wikipedia value";
    }

    @Override public String toString() { return getDisplayName(); }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }

    @Override public boolean equals(Object other) {
        return other instanceof WikipediaFragment value
                && sameEvidence(value);
    }

    @Override public int hashCode() { return getIdentifier().hashCode(); }

    private boolean sameEvidence(WikipediaFragment value) {
        if (value == null || !text.equals(value.text) || !media.equals(value.media)
                || links.size() != value.links.size()) return false;
        for (int index = 0; index < links.size(); index++) {
            WikipediaArticle.ArticleLink left = links.get(index);
            WikipediaArticle.ArticleLink right = value.links.get(index);
            if (!left.text().equals(right.text())
                    || !left.targetTitle().equals(right.targetTitle())
                    || left.start() != right.start() || left.end() != right.end()) return false;
        }
        return true;
    }

    /** Serializable media metadata local to retained Wikipedia evidence. */
    public static final class Media implements MediaValue {
        private final String label;
        private final String url;
        private final boolean svg;

        @JsonCreator
        public Media(@JsonProperty("label") String label,
                @JsonProperty("url") String url,
                @JsonProperty("svg") boolean svg) {
            this.label = label == null ? "" : label.trim();
            this.url = url == null ? "" : url.trim();
            this.svg = svg;
        }

        @Override public String mediaLabel() { return label; }
        @Override public String mediaUrl() { return url; }
        @Override public boolean mediaSvg() { return svg; }

        @Override public boolean equals(Object other) {
            return other instanceof Media value && label.equals(value.label)
                    && url.equals(value.url) && svg == value.svg;
        }

        @Override public int hashCode() { return Objects.hash(label, url, svg); }
    }
}
