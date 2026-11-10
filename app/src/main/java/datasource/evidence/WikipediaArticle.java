package datasource.evidence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.annotations.Hidden;
import objectview.annotations.Inline;
import objectview.annotations.Label;
import objectview.annotations.Link;

import java.util.List;
import java.util.Objects;

/**
 * One retained, versioned English-Wikipedia article before any domain recipe interprets it.
 *
 * <p>The hierarchy is source structure, not inferred domain data. Sections and blocks are
 * deliberately ordinary ObjectView values so the exact material acquired for later source
 * discovery can be inspected with the same search/view machinery as every other value.</p>
 */
public final class WikipediaArticle extends ViewableAdapter {
    @Hidden private final SourceDocument document;
    @Hidden private final String text;
    @DisplayField @Link private final String article;
    private final String revision;
    private final List<String> categories;
    @Inline private final List<Section> sections;

    @JsonCreator
    public WikipediaArticle(
            @JsonProperty("document") SourceDocument document,
            @JsonProperty("text") String text,
            @JsonProperty("categories") List<String> categories,
            @JsonProperty("sections") List<Section> sections) {
        this.document = Objects.requireNonNull(document, "Article document is required");
        this.text = text == null ? "" : text;
        this.article = linked(document.title(), document.url());
        this.revision = document.revision();
        this.categories = immutable(categories);
        this.sections = immutable(sections);
    }

    public SourceDocument document() { return document; }
    public String text() { return text; }
    public List<String> categories() { return categories; }
    public List<Section> sections() { return sections; }

    public List<ArticleIntroduction.Link> articleLinks() {
        return sections.stream().flatMap(section -> links(section).stream())
                .map(link -> new ArticleIntroduction.Link(link.text(), link.targetTitle(),
                        link.start(), link.end())).distinct().toList();
    }

    @Override public String getIdentifier() { return document.documentId(); }
    @Override public String getDisplayName() { return document.title(); }

    /** One heading and the source blocks owned directly by it. */
    public static final class Section extends ViewableAdapter {
        @DisplayField private final String heading;
        private final int level;
        @Inline private final List<Block> blocks;
        @Inline private final List<Section> sections;
        @Hidden private final int start;
        @Hidden private final int end;

        @JsonCreator
        public Section(
                @JsonProperty("heading") String heading,
                @JsonProperty("level") int level,
                @JsonProperty("blocks") List<Block> blocks,
                @JsonProperty("sections") List<Section> sections,
                @JsonProperty("start") int start,
                @JsonProperty("end") int end) {
            this.heading = clean(heading, "Introduction");
            this.level = Math.max(0, level);
            this.blocks = immutable(blocks);
            this.sections = immutable(sections);
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("Article section range is invalid");
            }
            this.start = start;
            this.end = end;
        }

        public String heading() { return heading; }
        public int level() { return level; }
        public List<Block> blocks() { return blocks; }
        public List<Section> sections() { return sections; }
        public int start() { return start; }
        public int end() { return end; }

        @Override public String getIdentifier() { return heading + '@' + start; }
        @Override public String getDisplayName() { return heading; }
    }

    /**
     * A paragraph, list item, quotation, table, definition entry or caption retained under
     * its source heading. The kind is the HTML structure; {@code element} is the source
     * element as the page names it ({@code table.infobox.vcard}, {@code ol.references > li}),
     * so an infobox, navigation box or footnote is told apart by what the page states,
     * not by a meaning assigned here.
     */
    public static final class Block extends ViewableAdapter {
        public enum Kind { PARAGRAPH, LIST_ITEM, QUOTATION, TABLE, DEFINITION, CAPTION }

        @DisplayField private final String block;
        private final Kind kind;
        @Label("Source element") private final String element;
        @Label("Article text") private final String value;
        @Inline private final List<ArticleLink> links;
        @Hidden private final int start;
        @Hidden private final int end;

        public Block(String block, Kind kind, String value, List<ArticleLink> links,
                     int start, int end) {
            this(block, kind, "", value, links, start, end);
        }

        /** {@code element} is empty for blocks retained before it was recorded. */
        @JsonCreator
        public Block(
                @JsonProperty("block") String block,
                @JsonProperty("kind") Kind kind,
                @JsonProperty("element") String element,
                @JsonProperty("value") String value,
                @JsonProperty("links") List<ArticleLink> links,
                @JsonProperty("start") int start,
                @JsonProperty("end") int end) {
            this.block = clean(block, "Article block");
            this.kind = kind == null ? Kind.PARAGRAPH : kind;
            this.element = element == null ? "" : element.trim();
            this.value = value == null ? "" : value.trim();
            this.links = immutable(links);
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("Article block range is invalid");
            }
            this.start = start;
            this.end = end;
        }

        public Kind kind() { return kind; }
        public String element() { return element; }
        public String value() { return value; }
        public List<ArticleLink> links() { return links; }
        public int start() { return start; }
        public int end() { return end; }

        @Override public String getIdentifier() { return block + '@' + start; }
        @Override public String getDisplayName() { return block; }
    }

    /** A source link inside one retained block, preserving its exact article-text range. */
    public static final class ArticleLink extends ViewableAdapter {
        @Hidden private final String text;
        @Hidden private final String targetTitle;
        @DisplayField @Link private final String article;
        @Hidden private final int start;
        @Hidden private final int end;

        @JsonCreator
        public ArticleLink(
                @JsonProperty("text") String text,
                @JsonProperty("targetTitle") String targetTitle,
                @JsonProperty("start") int start,
                @JsonProperty("end") int end) {
            this.text = clean(text, "Article link");
            this.targetTitle = clean(targetTitle, this.text);
            this.article = linked(this.text,
                    wikipedia.WikipediaArticleClient.pageUrl(this.targetTitle));
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("Article link range is invalid");
            }
            this.start = start;
            this.end = end;
        }

        public String text() { return text; }
        public String targetTitle() { return targetTitle; }
        public int start() { return start; }
        public int end() { return end; }

        @Override public String getIdentifier() { return targetTitle + '@' + start; }
        @Override public String getDisplayName() { return text; }
    }

    private static String linked(String label, String url) {
        String target = url == null ? "" : url.trim();
        return target.isBlank() ? "" : clean(label, target) + '|' + target;
    }

    private static String clean(String value, String fallback) {
        String result = value == null ? "" : value.trim();
        return result.isBlank() ? fallback : result;
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : values.stream()
                .filter(Objects::nonNull).toList();
    }

    private static List<ArticleLink> links(Section section) {
        java.util.ArrayList<ArticleLink> result = new java.util.ArrayList<>();
        section.blocks().forEach(block -> result.addAll(block.links()));
        section.sections().forEach(child -> result.addAll(links(child)));
        return result;
    }
}
