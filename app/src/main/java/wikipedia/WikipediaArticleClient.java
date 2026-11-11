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
import work.Query;
import work.QueryContext;

import java.net.URI;
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
        Parsed parsed = parseStructuredWithElements(root);
        if (parsed == null) return null;
        boolean parse = !root.path("parse").isMissingNode() && !root.path("parse").isNull();
        return new StructuredPage(parsed.article(),
                parse ? WikipediaInfoboxClient.parseResponse(root) : null, parsed.elements());
    }

    private record Parsed(Article article,
                          List<ArticleElementInventory.ElementKind> elements) { }

    static Article parseStructured(JsonNode root) {
        Parsed parsed = parseStructuredWithElements(root);
        return parsed == null ? null : parsed.article();
    }

    private static Parsed parseStructuredWithElements(JsonNode root) {
        JsonNode page = root.path("parse");
        if (page.isMissingNode() || page.isNull()) {
            Article extract = parse(root);
            return extract == null ? null : new Parsed(extract, List.of());
        }
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
        return new Parsed(new Article(document, parsed.text(), categories, parsed.links(),
                parsed.sections(), structured), parsed.elements());
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
        Element body = Jsoup.parseBodyFragment(extract, "https://en.wikipedia.org/").body();
        WikipediaHtmlFragmentParser.Parsed fragment =
                WikipediaHtmlFragmentParser.parseChildren(body);
        String normalized = fragment.value().text();
        List<ArticleIntroduction.Link> links = fragment.links();
        java.util.Map<Element, WikipediaHtmlFragmentParser.Span> spans = fragment.spans();
        java.util.Map<Element, String> outcomes = new java.util.IdentityHashMap<>();
        List<WikipediaArticle.Section> structured =
                structuredSections(body, normalized, links, outcomes, spans);
        return new ParsedArticleText(normalized, links,
                sections(body, normalized, spans), structured,
                ArticleElementInventory.of(body, outcomes));
    }

    /**
     * Keeps the heading tree and meaningful source blocks without retaining presentation
     * markup. Offsets refer to the same normalized article text used by evidence fragments.
     */
    /** What {@link #structuredSections} did with an element it read. An element it did
     *  not read was not retained. */
    static final String SECTION_HEADING = "Section heading";
    static final String INSIDE_RETAINED_BLOCK = "Inside a retained block";
    static final String DEFINITION_TITLE = "Definition title";
    static final String LIST_ITEM = "List item";
    static final String NESTED_LIST = "Nested list";
    static final String TABLE_CAPTION = "Table caption";
    static final String TABLE_ROW = "Table row";
    static final String TABLE_HEADER_CELL = "Table header cell";
    static final String TABLE_DATA_CELL = "Table data cell";
    static final String EMPTY = "Empty";
    static final String NOT_RETAINED = "Not retained";

    /**
     * {@code outcomes} receives, for every element read, what was done with it: a block
     * kind, or why no block was made. The element inventory reports these recorded
     * outcomes, so it states what the structure did rather than predicting it.
     *
     * <p>Positions come from {@code spans}, recorded by the walk that wrote {@code text}.
     * They used to be found by searching the text for each element's own text, which was
     * built differently (table cells joined, nested blocks spaced rather than broken), so
     * 94 list items, quotations, paragraphs and every multi-cell table of one article were
     * silently dropped, and a word matching a later heading could cut a section short
     * (#464). A block's value is now exactly its slice of the article text.</p>
     */
    private static List<WikipediaArticle.Section> structuredSections(
            Element body, String text, List<ArticleIntroduction.Link> links,
            java.util.Map<Element, String> outcomes,
            java.util.Map<Element, WikipediaHtmlFragmentParser.Span> spans) {
        MutableSection introduction = new MutableSection("Introduction", 0, 0);
        List<MutableSection> roots = new ArrayList<>();
        roots.add(introduction);
        List<MutableSection> stack = new ArrayList<>();
        stack.add(introduction);
        int[] blockNumber = {0};
        List<Element> retainedBlocks = new ArrayList<>();
        for (Element element : body.select(RETAINED_ELEMENTS)) {
            if (outcomes.containsKey(element)) continue;
            String tag = element.normalName();
            if (tag.matches("h[1-6]")) {
                String heading = element.text().trim();
                if (heading.isBlank()) {
                    outcomes.put(element, EMPTY);
                    continue;
                }
                outcomes.put(element, SECTION_HEADING);
                int level = Character.digit(tag.charAt(1), 10);
                int start = spanOf(spans, element, text).start();
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
                continue;
            }
            if (insideRetainedBlock(element)) {
                outcomes.put(element, INSIDE_RETAINED_BLOCK);
                continue;
            }
            if (tag.equals("dt") && hasFollowingDefinition(element)) {
                outcomes.put(element, DEFINITION_TITLE);
                continue;
            }
            DefinitionEntry definition = definitionStartingAt(element, spans, text);
            if (definition != null) {
                definition.terms().forEach(term -> outcomes.put(term, DEFINITION_TITLE));
                outcomes.put(definition.descriptions().getFirst(),
                        WikipediaArticle.Block.Kind.DEFINITION.name());
                definition.descriptions().stream().skip(1).forEach(description ->
                        outcomes.put(description, INSIDE_RETAINED_BLOCK));
                retainedBlocks.addAll(definition.terms());
                retainedBlocks.addAll(definition.descriptions());
                List<WikipediaArticle.ArticleLink> blockLinks = linksInRange(
                        links, definition.start(), definition.end());
                stack.getLast().blocks.add(new WikipediaArticle.Block(
                        definition.title(), WikipediaArticle.Block.Kind.DEFINITION,
                        definition.sourceElement(),
                        text.substring(definition.start(), definition.end()), blockLinks,
                        definition.start(), definition.end()));
                continue;
            }
            WikipediaHtmlFragmentParser.Span span = spanOf(spans, element, text);
            if (span.end() <= span.start()) {
                outcomes.put(element, EMPTY);
                continue;
            }
            int start = span.start();
            int end = span.end();
            String value = text.substring(start, end);
            WikipediaArticle.Block.Kind kind = blockKind(tag);
            outcomes.put(element, kind.name());
            retainedBlocks.add(element);
            WikipediaArticle.ArticleList list = kind == WikipediaArticle.Block.Kind.LIST
                    ? articleList(element, text, links, spans, outcomes) : null;
            WikipediaArticle.ArticleTable table = kind == WikipediaArticle.Block.Kind.TABLE
                    ? articleTable(element, text, links, spans, outcomes) : null;
            String structuralLabel = switch (kind) {
                case PARAGRAPH -> "Paragraph ";
                case LIST -> tag.equals("ol") ? "Ordered list " : "Unordered list ";
                case LIST_ITEM -> "List item ";
                case QUOTATION -> "Quotation ";
                case TABLE -> "Table ";
                case DEFINITION -> "Definition ";
                case CAPTION -> "Caption ";
            } + (++blockNumber[0]);
            String label = table != null && !table.caption().isBlank()
                    ? table.caption() : structuralLabel;
            List<WikipediaArticle.ArticleLink> blockLinks = linksInRange(links, start, end);
            stack.getLast().blocks.add(new WikipediaArticle.Block(
                    label, kind, ArticleElementInventory.sourceElement(element), value,
                    blockLinks, list, table, start, end));
        }
        // Whatever a retained block contains is in its value, whether or not the block's
        // own structure names it (a table's tbody, the rows of a table inside a cell).
        for (Element block : retainedBlocks) {
            for (Element inner : block.getAllElements()) {
                if (inner != block) outcomes.putIfAbsent(inner, INSIDE_RETAINED_BLOCK);
            }
        }
        for (MutableSection section : stack) {
            if (section.end <= 0) section.end = text.length();
        }
        return roots.stream().map(section -> section.freeze(text.length())).toList();
    }

    /** A definition list says that one or more terms title the descriptions that follow.
     * Keep that authored relation in the source artifact instead of emitting unrelated
     * {@code dt}/{@code dd} blocks and asking discovery views to reconstruct it. */
    private static DefinitionEntry definitionStartingAt(
            Element element, java.util.Map<Element, WikipediaHtmlFragmentParser.Span> spans,
            String text) {
        if (element == null || !element.normalName().equals("dd")) return null;
        Element previous = element.previousElementSibling();
        if (previous == null || !previous.normalName().equals("dt")) return null;

        List<Element> terms = new ArrayList<>();
        for (Element term = previous;
                term != null && term.normalName().equals("dt");
                term = term.previousElementSibling()) {
            terms.addFirst(term);
        }
        List<Element> descriptions = new ArrayList<>();
        for (Element description = element;
                description != null && description.normalName().equals("dd");
                description = description.nextElementSibling()) {
            descriptions.add(description);
        }
        String title = terms.stream().map(Element::text).map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.joining(" / "));
        if (title.isBlank() || descriptions.isEmpty()) return null;

        WikipediaHtmlFragmentParser.Span first = spanOf(spans, terms.getFirst(), text);
        WikipediaHtmlFragmentParser.Span last = spanOf(
                spans, descriptions.getLast(), text);
        if (last.end() <= first.start()) return null;
        Element parent = terms.getFirst().parent();
        String source = parent != null && parent == descriptions.getFirst().parent()
                ? ArticleElementInventory.name(parent) + " > "
                        + ArticleElementInventory.name(terms.getFirst()) + " + "
                        + ArticleElementInventory.name(descriptions.getFirst())
                : ArticleElementInventory.sourceElement(terms.getFirst()) + " + "
                        + ArticleElementInventory.sourceElement(descriptions.getFirst());
        return new DefinitionEntry(terms, descriptions, title, source,
                first.start(), last.end());
    }

    private static boolean hasFollowingDefinition(Element term) {
        if (term == null || !term.normalName().equals("dt")) return false;
        Element next = term.nextElementSibling();
        while (next != null && next.normalName().equals("dt")) {
            next = next.nextElementSibling();
        }
        return next != null && next.normalName().equals("dd");
    }

    private record DefinitionEntry(
            List<Element> terms, List<Element> descriptions, String title,
            String sourceElement, int start, int end) {
        private DefinitionEntry {
            terms = List.copyOf(terms);
            descriptions = List.copyOf(descriptions);
        }
    }

    private static WikipediaArticle.ArticleList articleList(
            Element list, String text, List<ArticleIntroduction.Link> links,
            java.util.Map<Element, WikipediaHtmlFragmentParser.Span> spans,
            java.util.Map<Element, String> outcomes) {
        return articleList(list, "", text, links, spans, outcomes);
    }

    private static WikipediaArticle.ArticleList articleList(
            Element list, String ownerPath, String text,
            List<ArticleIntroduction.Link> links,
            java.util.Map<Element, WikipediaHtmlFragmentParser.Span> spans,
            java.util.Map<Element, String> outcomes) {
        boolean ordered = list.normalName().equals("ol");
        List<WikipediaArticle.ListItem> items = new ArrayList<>();
        int position = 0;
        for (Element child : list.children()) {
            if (!child.normalName().equals("li")) continue;
            position++;
            String itemPath = ownerPath.isBlank() ? String.valueOf(position)
                    : ownerPath + '.' + position;
            items.add(listItem(child, itemPath, text, links, spans, outcomes));
        }
        String kind = ordered ? "Ordered list" : "Unordered list";
        WikipediaHtmlFragmentParser.Span span = spanOf(spans, list, text);
        return new WikipediaArticle.ArticleList(
                ownerPath.isBlank() ? kind : kind + " at item " + ownerPath,
                ordered, items, span.start(), span.end());
    }

    private static WikipediaArticle.ListItem listItem(
            Element item, String path, String text, List<ArticleIntroduction.Link> links,
            java.util.Map<Element, WikipediaHtmlFragmentParser.Span> spans,
            java.util.Map<Element, String> outcomes) {
        outcomes.put(item, LIST_ITEM);
        WikipediaHtmlFragmentParser.Span span = spanOf(spans, item, text);
        List<WikipediaArticle.ArticleList> nestedLists = new ArrayList<>();
        for (Element nested : nestedListsOwnedBy(item)) {
            outcomes.put(nested, NESTED_LIST);
            nestedLists.add(articleList(nested, path, text, links, spans, outcomes));
        }
        return new WikipediaArticle.ListItem("Item " + path,
                text.substring(span.start(), span.end()),
                linksInRange(links, span.start(), span.end()), nestedLists,
                span.start(), span.end());
    }

    /** Nested lists whose nearest list-item ancestor is this item. */
    private static List<Element> nestedListsOwnedBy(Element item) {
        return item.select("ol, ul").stream().filter(list -> {
            for (Element parent = list.parent(); parent != null; parent = parent.parent()) {
                if (parent.normalName().equals("li")) return parent == item;
            }
            return false;
        }).toList();
    }

    private static WikipediaArticle.ArticleTable articleTable(
            Element table, String text, List<ArticleIntroduction.Link> links,
            java.util.Map<Element, WikipediaHtmlFragmentParser.Span> spans,
            java.util.Map<Element, String> outcomes) {
        Element captionElement = table.children().stream()
                .filter(child -> child.normalName().equals("caption"))
                .findFirst().orElse(null);
        String caption = "";
        List<WikipediaArticle.ArticleLink> captionLinks = List.of();
        if (captionElement != null) {
            outcomes.put(captionElement, TABLE_CAPTION);
            WikipediaHtmlFragmentParser.Span span = spanOf(spans, captionElement, text);
            caption = text.substring(span.start(), span.end());
            captionLinks = linksInRange(links, span.start(), span.end());
        }

        List<WikipediaArticle.TableRow> rows = new ArrayList<>();
        for (Element row : table.select("tr")) {
            if (nearestTable(row) != table) continue;
            outcomes.put(row, TABLE_ROW);
            WikipediaHtmlFragmentParser.Span rowSpan = spanOf(spans, row, text);
            List<WikipediaArticle.TableCell> cells = new ArrayList<>();
            int cellNumber = 0;
            for (Element cell : row.children()) {
                boolean header = cell.normalName().equals("th");
                if (!header && !cell.normalName().equals("td")) continue;
                cellNumber++;
                outcomes.put(cell, header ? TABLE_HEADER_CELL : TABLE_DATA_CELL);
                WikipediaHtmlFragmentParser.Span cellSpan = spanOf(spans, cell, text);
                cells.add(new WikipediaArticle.TableCell(
                        (header ? "Header " : "Cell ") + cellNumber,
                        header, cell.attr("scope"), positiveInt(cell.attr("rowspan")),
                        positiveInt(cell.attr("colspan")),
                        text.substring(cellSpan.start(), cellSpan.end()),
                        linksInRange(links, cellSpan.start(), cellSpan.end()),
                        cellSpan.start(), cellSpan.end()));
            }
            rows.add(new WikipediaArticle.TableRow("Row " + (rows.size() + 1), cells,
                    rowSpan.start(), rowSpan.end()));
        }
        String title = caption.isBlank() ? "Table" : caption;
        WikipediaHtmlFragmentParser.Span span = spanOf(spans, table, text);
        return new WikipediaArticle.ArticleTable(title, caption, captionLinks, rows,
                span.start(), span.end());
    }

    private static Element nearestTable(Element element) {
        for (Element parent = element.parent(); parent != null; parent = parent.parent()) {
            if (parent.normalName().equals("table")) return parent;
        }
        return null;
    }

    private static int positiveInt(String value) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static List<WikipediaArticle.ArticleLink> linksInRange(
            List<ArticleIntroduction.Link> links, int start, int end) {
        return links.stream().filter(link -> link.start() >= start && link.end() <= end)
                .map(link -> new WikipediaArticle.ArticleLink(link.text(),
                        link.targetTitle(), link.start(), link.end())).toList();
    }

    /** The elements {@link #structuredSections} reads: headings open sections, the rest
     *  become blocks. Everything else in the article is not retained. */
    static final String RETAINED_ELEMENTS =
            "h1, h2, h3, h4, h5, h6, p, ol, ul, li, blockquote, table, dt, dd, "
                    + "figcaption";

    private static WikipediaArticle.Block.Kind blockKind(String tag) {
        return switch (tag) {
            case "ol", "ul" -> WikipediaArticle.Block.Kind.LIST;
            case "li" -> WikipediaArticle.Block.Kind.LIST_ITEM;
            case "blockquote" -> WikipediaArticle.Block.Kind.QUOTATION;
            case "table" -> WikipediaArticle.Block.Kind.TABLE;
            case "dt", "dd" -> WikipediaArticle.Block.Kind.DEFINITION;
            case "figcaption" -> WikipediaArticle.Block.Kind.CAPTION;
            default -> WikipediaArticle.Block.Kind.PARAGRAPH;
        };
    }

    /** A nested list/table block is represented by its outermost retained block once. */
    private static boolean insideRetainedBlock(Element element) {
        for (Element parent = element.parent(); parent != null; parent = parent.parent()) {
            String tag = parent.normalName();
            if (tag.equals("p") || tag.equals("li") || tag.equals("blockquote")
                    || tag.equals("ol") || tag.equals("ul") || tag.equals("table")
                    || tag.equals("dt") || tag.equals("dd")
                    || tag.equals("figcaption")) return true;
        }
        return false;
    }

    /** The recorded span, clamped to the final text (its trailing whitespace is trimmed
     *  after the walk) and trimmed of the breaks the walk wrote around the element. */
    private static WikipediaHtmlFragmentParser.Span spanOf(
            java.util.Map<Element, WikipediaHtmlFragmentParser.Span> spans,
            Element element, String text) {
        WikipediaHtmlFragmentParser.Span recorded = spans.get(element);
        if (recorded == null) return new WikipediaHtmlFragmentParser.Span(0, 0);
        int start = Math.min(recorded.start(), text.length());
        int end = Math.min(recorded.end(), text.length());
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        return new WikipediaHtmlFragmentParser.Span(start, end);
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

    /** Flat sections, started where the walk wrote each heading — never by searching the
     *  text for the heading's words, which an earlier paragraph may also contain. */
    private static List<ArticleSection> sections(Element body, String text,
            java.util.Map<Element, WikipediaHtmlFragmentParser.Span> spans) {
        if (text == null || text.isBlank()) return List.of();
        List<SectionStart> starts = new ArrayList<>();
        for (Element heading : body.select("h1, h2, h3, h4, h5, h6")) {
            String title = heading.text().trim();
            if (title.isBlank()) continue;
            starts.add(new SectionStart(title, spanOf(spans, heading, text).start()));
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

    /** {@code elements} is the structural-HTML-element inventory of the same HTML. */
    public record ParsedArticleText(String text, List<ArticleIntroduction.Link> links,
                                    List<ArticleSection> sections,
                                    List<WikipediaArticle.Section> structuredSections,
                                    List<ArticleElementInventory.ElementKind> elements) {
        public ParsedArticleText(String text, List<ArticleIntroduction.Link> links,
                                 List<ArticleSection> sections,
                                 List<WikipediaArticle.Section> structuredSections) {
            this(text, links, sections, structuredSections, List.of());
        }
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
            elements = elements == null ? List.of() : List.copyOf(elements);
        }
    }

    /** One structured article, the infobox read from the same response (or null), and the
     *  structural-HTML-element inventory of its HTML. */
    public record StructuredPage(Article article, InfoboxParameters infobox,
                                 List<ArticleElementInventory.ElementKind> elements) {
        public StructuredPage {
            article = java.util.Objects.requireNonNull(article);
            elements = elements == null ? List.of() : List.copyOf(elements);
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
