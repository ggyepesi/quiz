package wikipedia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import datasource.evidence.ContentDigest;
import datasource.evidence.ArticleIntroduction;
import datasource.evidence.InfoboxParameters;
import datasource.evidence.SourceDocument;
import datasource.evidence.WikipediaArticle;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import work.Query;
import work.QueryContext;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reads immutable, versioned English Wikipedia article text. */
public final class WikipediaArticleClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Fetcher fetcher;

    public WikipediaArticleClient() {
        this(uri -> {
            try (java.io.InputStream in = objectview.utils.UrlOpener.open(uri.toURL())) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        });
    }

    public WikipediaArticleClient(Fetcher fetcher) {
        this.fetcher = java.util.Objects.requireNonNull(fetcher);
    }

    public Query<Article> byTitle(String title) {
        String requested = title == null ? "" : title.trim();
        if (requested.isBlank()) throw new IllegalArgumentException("Article title is required");
        URI uri = api(requested);
        return new Query<>() {
            @Override public String purpose() { return "Read versioned Wikipedia article"; }
            @Override public String skeleton() { return "extracts + revision + content digest"; }
            @Override public String queryType() { return "Wikipedia API"; }
            @Override public String description() { return "English Wikipedia article text"; }
            @Override public Map<String, String> parameters() {
                return Map.of("title", requested);
            }
            @Override public Article execute(QueryContext context) throws Exception {
                return context.step("Read versioned article text", "Wikipedia API", skeleton(),
                        parameters(), step -> {
                            step.request(uri.toString());
                            Article article = parse(JSON.readTree(fetcher.fetch(uri)));
                            step.summary(article == null ? "Article unavailable"
                                    : article.text().length() + " character(s), "
                                    + article.links().size() + " internal link(s), "
                                    + article.categories().size() + " categor"
                                    + (article.categories().size() == 1 ? "y" : "ies"));
                            return article;
                        });
            }
            @Override public int rowCount(Article result) { return result == null ? 0 : 1; }
        };
    }

    /**
     * Reads the full parsed article for an explicit inspection request. Unlike the
     * lightweight extracts endpoint used by ordinary evidence acquisition, this retains
     * real internal links, tables and list structure. It is intentionally not the default:
     * a selected sample can justify the larger response; a generation-wide introduction
     * acquisition cannot.
     */
    public Query<StructuredPage> structuredByTitle(String title) {
        String requested = title == null ? "" : title.trim();
        if (requested.isBlank()) throw new IllegalArgumentException("Article title is required");
        URI uri = parseApi(requested);
        return new Query<>() {
            @Override public String purpose() { return "Read structured Wikipedia article"; }
            @Override public String skeleton() {
                return "parsed HTML + wikitext + revision + sections + links + categories"
                        + " + infobox";
            }
            @Override public String queryType() { return "Wikipedia API"; }
            @Override public String description() {
                return "Complete English Wikipedia article structure";
            }
            @Override public Map<String, String> parameters() {
                return Map.of("title", requested);
            }
            @Override public StructuredPage execute(QueryContext context) throws Exception {
                return context.step("Read structured article", "Wikipedia API", skeleton(),
                        parameters(), step -> {
                            step.request(uri.toString());
                            StructuredPage page =
                                    parseStructuredPage(JSON.readTree(fetcher.fetch(uri)));
                            Article article = page == null ? null : page.article();
                            step.summary(article == null ? "Article unavailable"
                                    : article.text().length() + " character(s), "
                                    + article.links().size() + " internal link(s), "
                                    + article.categories().size() + " visible categories, "
                                    + (page.infobox() == null ? "no infobox" : "infobox"));
                            return page;
                        });
            }
            @Override public int rowCount(StructuredPage result) {
                return result == null || result.article() == null ? 0 : 1;
            }
        };
    }

    static Article parse(JsonNode root) {
        JsonNode pages = root.path("query").path("pages");
        JsonNode page = pages.isArray() && !pages.isEmpty() ? pages.get(0) : null;
        if (page == null || page.path("missing").asBoolean(false)) return null;
        String title = page.path("title").asText("");
        ParsedArticleText parsed = parseArticleText(page.path("extract").asText(""));
        String text = parsed.text();
        String revision = page.path("lastrevid").asText("");
        if (revision.isBlank() && page.path("revisions").isArray()
                && !page.path("revisions").isEmpty()) {
            revision = page.path("revisions").get(0).path("revid").asText("");
        }
        if (title.isBlank() || revision.isBlank()) return null;
        List<String> categories = new ArrayList<>();
        JsonNode categoryNodes = page.path("categories");
        if (categoryNodes.isArray()) {
            for (JsonNode category : categoryNodes) {
                String value = category.path("title").asText("")
                        .replaceFirst("^Category:", "").trim();
                if (!value.isBlank()) categories.add(value);
            }
        }
        SourceDocument document = sourceDocument(
                title, revision, text, categories, parsed.links());
        WikipediaArticle structured = new WikipediaArticle(
                document, text, categories, parsed.structuredSections());
        return new Article(document, text, categories, parsed.links(), parsed.sections(),
                structured);
    }

    /**
     * The article and its infobox from ONE parse response, so both describe the same
     * revision. Discovery used to request the infobox separately: two requests per
     * article, and an edit between them paired an article with another revision's
     * infobox (#461). The infobox is read by its own parser, from this response.
     */
    static StructuredPage parseStructuredPage(JsonNode root) {
        Article article = parseStructured(root);
        if (article == null) return null;
        boolean parsed = !root.path("parse").isMissingNode() && !root.path("parse").isNull();
        return new StructuredPage(article,
                parsed ? WikipediaInfoboxClient.parseResponse(root) : null);
    }

    static Article parseStructured(JsonNode root) {
        JsonNode page = root.path("parse");
        if (page.isMissingNode() || page.isNull()) return parse(root);
        String title = page.path("title").asText("").trim();
        String revision = page.path("revid").asText("").trim();
        String html = page.path("text").asText("");
        if (title.isBlank() || revision.isBlank()) return null;
        List<String> categories = new ArrayList<>();
        JsonNode categoryNodes = page.path("categories");
        if (categoryNodes.isArray()) for (JsonNode category : categoryNodes) {
            if (category.path("hidden").asBoolean(false)) continue;
            String value = category.path("category").asText("")
                    .replace('_', ' ').trim();
            if (!value.isBlank()) categories.add(value);
        }
        ParsedArticleText parsed = parseArticleText(html);
        SourceDocument document = sourceDocument(title, revision, parsed.text(),
                categories, parsed.links());
        WikipediaArticle structured = new WikipediaArticle(document, parsed.text(),
                categories, parsed.structuredSections());
        return new Article(document, parsed.text(), categories, parsed.links(),
                parsed.sections(), structured);
    }

    /** One construction of the versioned document evidence used by every Wikipedia
     *  article acquisition, whether it retained the full text or only the introduction. */
    public static SourceDocument sourceDocument(String title, String revision,
            String text, List<String> categories) {
        return sourceDocument(title, revision, text, categories, List.of());
    }

    /** Builds one digest over every retained part of the article evidence. */
    public static SourceDocument sourceDocument(String title, String revision,
            String text, List<String> categories, List<ArticleIntroduction.Link> links) {
        List<String> safeCategories = categories == null ? List.of() : categories;
        List<ArticleIntroduction.Link> safeLinks = links == null ? List.of() : links;
        String safeText = text == null ? "" : text;
        String linkEvidence = safeLinks.isEmpty() ? "" : "\nLinks:\n"
                + safeLinks.stream().map(link -> link.start() + ":" + link.end()
                        + ":" + link.text() + "->" + link.targetTitle())
                        .collect(java.util.stream.Collectors.joining("\n"));
        return new SourceDocument("Wikipedia (English)", title, title,
                pageUrl(title), revision, new ContentDigest("sha256",
                sha256(safeText + "\nCategories:\n"
                        + String.join("\n", safeCategories) + linkEvidence)),
                Instant.now().toString());
    }

    /**
     * Converts a MediaWiki HTML extract into stable plain text while retaining the
     * internal links that disambiguate equal labels. A plain-text extract remains valid
     * input, which keeps old snapshots and test fixtures readable.
     */
    public static ParsedArticleText parseArticleText(String extract) {
        if (extract == null || extract.isBlank()) {
            return new ParsedArticleText("", List.of(), List.of());
        }
        Element body = Jsoup.parseBodyFragment(extract).body();
        List<String> headings = body.select("h1, h2, h3, h4, h5, h6").stream()
                .map(Element::text).map(String::trim).filter(value -> !value.isBlank()).toList();
        StringBuilder text = new StringBuilder();
        List<ArticleIntroduction.Link> links = new ArrayList<>();
        for (Node child : body.childNodes()) append(child, text, links);
        while (!text.isEmpty() && Character.isWhitespace(text.charAt(text.length() - 1))) {
            text.setLength(text.length() - 1);
        }
        String normalized = text.toString();
        return new ParsedArticleText(normalized, links,
                sections(normalized, headings), structuredSections(body, normalized, links));
    }

    /**
     * Keeps the heading tree and meaningful source blocks without retaining presentation
     * markup. Offsets refer to the same normalized article text used by evidence fragments.
     */
    private static List<WikipediaArticle.Section> structuredSections(
            Element body, String text, List<ArticleIntroduction.Link> links) {
        MutableSection introduction = new MutableSection("Introduction", 0, 0);
        List<MutableSection> roots = new ArrayList<>();
        roots.add(introduction);
        List<MutableSection> stack = new ArrayList<>();
        stack.add(introduction);
        int[] searchFrom = {0};
        int[] blockNumber = {0};
        for (Element element : body.select("h1, h2, h3, h4, h5, h6, p, li, blockquote, table")) {
            String tag = element.normalName();
            if (tag.matches("h[1-6]")) {
                String heading = element.text().trim();
                if (heading.isBlank()) continue;
                int level = Character.digit(tag.charAt(1), 10);
                int start = findText(text, heading, searchFrom[0]);
                if (start < 0) start = searchFrom[0];
                if (introduction.end == 0) introduction.end = start;
                while (!stack.isEmpty() && stack.getLast().level >= level) {
                    stack.removeLast().end = start;
                }
                MutableSection section = new MutableSection(heading, level, start);
                if (stack.size() == 1 && stack.getFirst() == introduction) {
                    roots.add(section);
                } else {
                    stack.getLast().children.add(section);
                }
                stack.add(section);
                searchFrom[0] = Math.min(text.length(), start + heading.length());
                continue;
            }
            if (insideRetainedBlock(element)) continue;
            String value = normalizeBlockText(element.text());
            if (value.isBlank()) continue;
            int start = findText(text, value, searchFrom[0]);
            if (start < 0) continue;
            int end = start + value.length();
            searchFrom[0] = end;
            WikipediaArticle.Block.Kind kind = switch (tag) {
                case "li" -> WikipediaArticle.Block.Kind.LIST_ITEM;
                case "blockquote" -> WikipediaArticle.Block.Kind.QUOTATION;
                case "table" -> WikipediaArticle.Block.Kind.TABLE;
                default -> WikipediaArticle.Block.Kind.PARAGRAPH;
            };
            String label = switch (kind) {
                case PARAGRAPH -> "Paragraph ";
                case LIST_ITEM -> "List item ";
                case QUOTATION -> "Quotation ";
                case TABLE -> "Table ";
            } + (++blockNumber[0]);
            List<WikipediaArticle.ArticleLink> blockLinks = links.stream()
                    .filter(link -> link.start() >= start && link.end() <= end)
                    .map(link -> new WikipediaArticle.ArticleLink(link.text(),
                            link.targetTitle(), link.start(), link.end())).toList();
            stack.getLast().blocks.add(new WikipediaArticle.Block(
                    label, kind, value, blockLinks, start, end));
        }
        for (MutableSection section : stack) {
            if (section.end <= 0) section.end = text.length();
        }
        return roots.stream().map(section -> section.freeze(text.length())).toList();
    }

    /** A nested list/table block is represented by its outermost retained block once. */
    private static boolean insideRetainedBlock(Element element) {
        for (Element parent = element.parent(); parent != null; parent = parent.parent()) {
            String tag = parent.normalName();
            if (tag.equals("p") || tag.equals("li") || tag.equals("blockquote")
                    || tag.equals("table")) return true;
        }
        return false;
    }

    private static String normalizeBlockText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static int findText(String text, String value, int from) {
        return text.indexOf(value, Math.max(0, from));
    }

    private static final class MutableSection {
        private final String heading;
        private final int level;
        private final int start;
        private int end;
        private final List<WikipediaArticle.Block> blocks = new ArrayList<>();
        private final List<MutableSection> children = new ArrayList<>();

        private MutableSection(String heading, int level, int start) {
            this.heading = heading;
            this.level = level;
            this.start = start;
        }

        private WikipediaArticle.Section freeze(int articleEnd) {
            int resolvedEnd = end <= 0 ? articleEnd : end;
            return new WikipediaArticle.Section(heading, level, blocks,
                    children.stream().map(child -> child.freeze(resolvedEnd)).toList(),
                    start, resolvedEnd);
        }
    }

    private static List<ArticleSection> sections(String text, List<String> headings) {
        if (text == null || text.isBlank()) return List.of();
        List<SectionStart> starts = new ArrayList<>();
        int searchFrom = 0;
        for (String heading : headings == null ? List.<String>of() : headings) {
            int start = text.indexOf(heading, searchFrom);
            if (start < 0) continue;
            starts.add(new SectionStart(heading, start));
            searchFrom = start + heading.length();
        }
        List<ArticleSection> result = new ArrayList<>();
        int first = starts.isEmpty() ? text.length() : starts.getFirst().start();
        if (first > 0) result.add(new ArticleSection("Introduction", 0, first));
        for (int index = 0; index < starts.size(); index++) {
            SectionStart start = starts.get(index);
            int end = index + 1 < starts.size()
                    ? starts.get(index + 1).start() : text.length();
            result.add(new ArticleSection(start.title(), start.start(), end));
        }
        return List.copyOf(result);
    }

    private record SectionStart(String title, int start) { }

    private static void append(Node node, StringBuilder text,
            List<ArticleIntroduction.Link> links) {
        if (node instanceof TextNode value) {
            appendText(text, value.getWholeText());
            return;
        }
        if (!(node instanceof Element element)) return;
        String tag = element.normalName();
        boolean block = switch (tag) {
            case "p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote" -> true;
            default -> false;
        };
        if (block) appendBoundary(text);
        if ("br".equals(tag)) {
            appendBoundary(text);
            return;
        }
        int start = text.length();
        for (Node child : element.childNodes()) append(child, text, links);
        int end = text.length();
        if ("a".equals(tag)) {
            while (start < end && Character.isWhitespace(text.charAt(start))) start++;
            while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
            String target = linkTarget(element);
            if (end > start && !target.isBlank()) {
                links.add(new ArticleIntroduction.Link(
                        text.substring(start, end), target, start, end));
            }
        }
        if (block) appendBoundary(text);
    }

    private static void appendText(StringBuilder out, String value) {
        if (value == null || value.isEmpty()) return;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character)) {
                if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n'
                        && out.charAt(out.length() - 1) != ' ') out.append(' ');
                continue;
            }
            out.append(character);
        }
    }

    private static void appendBoundary(StringBuilder text) {
        while (!text.isEmpty() && text.charAt(text.length() - 1) == ' ') {
            text.setLength(text.length() - 1);
        }
        if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') text.append('\n');
    }

    private static String linkTarget(Element link) {
        String title = link.attr("title").trim();
        if (!title.isBlank()) return title;
        String href = link.attr("href").trim();
        int wiki = href.indexOf("/wiki/");
        if (wiki < 0) return "";
        String encoded = href.substring(wiki + "/wiki/".length());
        int fragment = encoded.indexOf('#');
        if (fragment >= 0) encoded = encoded.substring(0, fragment);
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8).replace('_', ' ').trim();
    }

    private static URI api(String title) {
        return URI.create("https://en.wikipedia.org/w/api.php?action=query"
                + "&prop=extracts%7Crevisions%7Ccategories&cllimit=max&clshow=!hidden"
                + "&exsectionformat=plain"
                + "&rvprop=ids%7Ctimestamp&redirects=1&format=json&formatversion=2&titles="
                + encode(title));
    }

    private static URI parseApi(String title) {
        return URI.create("https://en.wikipedia.org/w/api.php?action=parse"
                + "&prop=text%7Cwikitext%7Csections%7Ccategories%7Clinks%7Crevid&redirects=1"
                + "&format=json&formatversion=2&page=" + encode(title));
    }

    public static String pageUrl(String title) {
        return "https://en.wikipedia.org/wiki/" + encode(title.replace(' ', '_'));
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public record ArticleSection(String title, int start, int end) {
        public ArticleSection {
            title = title == null || title.isBlank() ? "Introduction" : title.trim();
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("Article section range is invalid");
            }
        }
    }

    public record ParsedArticleText(String text, List<ArticleIntroduction.Link> links,
                                    List<ArticleSection> sections,
                                    List<WikipediaArticle.Section> structuredSections) {
        public ParsedArticleText(String text, List<ArticleIntroduction.Link> links) {
            this(text, links, text == null || text.isBlank()
                    ? List.of() : List.of(new ArticleSection(
                            "Introduction", 0, text.length())), List.of());
        }
        public ParsedArticleText(String text, List<ArticleIntroduction.Link> links,
                                 List<ArticleSection> sections) {
            this(text, links, sections, List.of());
        }
        public ParsedArticleText {
            text = text == null ? "" : text;
            links = links == null ? List.of() : List.copyOf(links);
            sections = sections == null ? List.of() : List.copyOf(sections);
            structuredSections = structuredSections == null
                    ? List.of() : List.copyOf(structuredSections);
        }
    }

    /** One structured article and the infobox read from the same response, or null. */
    public record StructuredPage(Article article, InfoboxParameters infobox) {
        public StructuredPage {
            article = java.util.Objects.requireNonNull(article);
        }
    }

    public record Article(SourceDocument document, String text, List<String> categories,
                          List<ArticleIntroduction.Link> links,
                          List<ArticleSection> sections,
                          WikipediaArticle structuredArticle) {
        public Article(SourceDocument document, String text, List<String> categories) {
            this(document, text, categories, List.of(), text == null || text.isBlank()
                    ? List.of() : List.of(new ArticleSection(
                            "Introduction", 0, text.length())), null);
        }
        public Article(SourceDocument document, String text, List<String> categories,
                List<ArticleIntroduction.Link> links) {
            this(document, text, categories, links, text == null || text.isBlank()
                    ? List.of() : List.of(new ArticleSection(
                            "Introduction", 0, text.length())), null);
        }
        public Article(SourceDocument document, String text, List<String> categories,
                List<ArticleIntroduction.Link> links, List<ArticleSection> sections) {
            this(document, text, categories, links, sections, null);
        }
        public Article(WikipediaArticle structuredArticle) {
            this(structuredArticle.document(), structuredArticle.text(),
                    structuredArticle.categories(), structuredArticle.articleLinks(),
                    flatSections(structuredArticle.sections()), structuredArticle);
        }
        public Article {
            document = java.util.Objects.requireNonNull(document);
            text = text == null ? "" : text;
            categories = categories == null ? List.of() : List.copyOf(categories);
            links = links == null ? List.of() : List.copyOf(links);
            sections = sections == null ? List.of() : List.copyOf(sections);
            if (structuredArticle == null) {
                List<WikipediaArticle.Section> structure = sections.stream()
                        .map(section -> new WikipediaArticle.Section(section.title(), 1,
                                List.of(), List.of(), section.start(), section.end()))
                        .toList();
                structuredArticle = new WikipediaArticle(
                        document, text, categories, structure);
            }
        }
        public String title() { return document.title(); }
        public String url() { return document.url(); }
        public String sectionAt(int offset) {
            return sections.stream().filter(section -> offset >= section.start()
                    && offset < section.end())
                    .min(java.util.Comparator.comparingInt(
                            section -> section.end() - section.start()))
                    .map(ArticleSection::title)
                    .orElse("Article");
        }

        private static List<ArticleSection> flatSections(
                List<WikipediaArticle.Section> sections) {
            java.util.ArrayList<ArticleSection> result = new java.util.ArrayList<>();
            flatten(sections, result);
            return List.copyOf(result);
        }

        private static void flatten(List<WikipediaArticle.Section> sections,
                List<ArticleSection> result) {
            if (sections == null) return;
            for (WikipediaArticle.Section section : sections) {
                result.add(new ArticleSection(section.heading(),
                        section.start(), section.end()));
                flatten(section.sections(), result);
            }
        }
    }

    @FunctionalInterface
    public interface Fetcher {
        String fetch(URI uri) throws Exception;
    }
}
