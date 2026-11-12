package wikipedia.discovery;

import datasource.evidence.WikipediaArticle;
import datasource.evidence.WikipediaFragment;
import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.annotations.Inline;
import objectview.annotations.Hidden;
import objectview.annotations.Label;
import objectview.annotations.Link;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.LinkedHashSet;
import java.util.List;

/** ObjectView rows used by the Wikipedia discovery workbench. */
public final class WikipediaDiscoveryRows {
    private static final int CAPTION_LIMIT = 140;

    private WikipediaDiscoveryRows() {}

    /** A read-only discovery grouping over the original selected domain instances. */
    public interface StructuralGroup extends objectview.Viewable {
        String groupName();
        List<WikidataDynamicObject> members();
    }

    public static final class SectionPattern extends ViewableAdapter
            implements StructuralGroup {
        @DisplayField private final String section;
        @Label("Heading") private final String heading;
        @Label("Articles") private final int articles;
        @Label("Selected articles") private final int selectedArticles;
        private final int missing;
        private final int occurrences;
        private final String levels;
        @Inline private final List<SectionExample> examples;
        @Hidden private final List<WikidataDynamicObject> members;

        SectionPattern(String heading, int articles, int selectedArticles,
                int occurrences, String levels, List<SectionExample> examples) {
            this(heading, articles, selectedArticles, occurrences, levels, examples,
                    List.of());
        }

        SectionPattern(String heading, int articles, int selectedArticles,
                int occurrences, String levels, List<SectionExample> examples,
                List<WikidataDynamicObject> members) {
            this.section = sectionCaption(heading, examples);
            this.heading = heading;
            this.articles = articles;
            this.selectedArticles = selectedArticles;
            this.missing = Math.max(0, selectedArticles - articles);
            this.occurrences = occurrences;
            this.levels = levels;
            this.examples = List.copyOf(examples);
            this.members = List.copyOf(members);
        }

        public String heading() { return heading; }
        public int articles() { return articles; }
        public int selectedArticles() { return selectedArticles; }
        public int occurrences() { return occurrences; }
        public List<SectionExample> examples() { return examples; }
        @Override public String groupName() { return "Section: " + section; }
        @Override public List<WikidataDynamicObject> members() { return members; }
        @Override public String getIdentifier() { return section.toLowerCase(); }
        @Override public String getDisplayName() { return section; }
    }

    public static final class SectionExample extends ViewableAdapter {
        @DisplayField @Link private final String article;
        @Label("Section path") private final String path;
        private final int level;
        @Inline private final List<WikipediaArticle.Block> blocks;
        @Hidden private final int start;

        SectionExample(String title, String path, int level,
                List<WikipediaArticle.Block> blocks, int start) {
            this.article = linked(title);
            this.path = path;
            this.level = level;
            this.blocks = List.copyOf(blocks);
            this.start = start;
        }

        public String title() { return label(article); }
        public String path() { return path; }
        public List<WikipediaArticle.Block> blocks() { return blocks; }
        @Override public String getIdentifier() { return title() + ':' + start; }
        @Override public String getDisplayName() { return title(); }
    }

    /** One retained source block at its exact article/section location. Definition terms
     * become the card title because the source page states that title; paragraphs and
     * other untitled blocks keep their stable structural label. */
    public static final class SectionContent extends ViewableAdapter
            implements StructuralGroup {
        @DisplayField private final String title;
        @Link private final String article;
        @Label("Section path") private final String sectionPath;
        @Label("Source position") private final String sourcePosition;
        private final WikipediaArticle.Block.Kind kind;
        @Label("Source element") private final String sourceElement;
        @Label("Article text") private final String text;
        @Inline private final List<WikipediaArticle.ArticleLink> links;
        @Inline private final WikipediaArticle.ArticleList list;
        @Inline private final WikipediaArticle.ArticleTable table;
        @Hidden private final int start;
        @Hidden private final List<WikidataDynamicObject> members;

        SectionContent(String article, String sectionPath, WikipediaArticle.Block block) {
            this(null, article, sectionPath, block);
        }

        SectionContent(WikidataDynamicObject member, String article, String sectionPath,
                WikipediaArticle.Block block) {
            this.sourcePosition = block == null ? "Article block" : block.getDisplayName();
            this.title = readableCaption(sourcePosition, block == null ? "" : block.value(),
                    authoredTitle(block));
            this.article = linked(article);
            this.sectionPath = sectionPath == null ? "" : sectionPath;
            this.kind = block == null ? WikipediaArticle.Block.Kind.PARAGRAPH : block.kind();
            this.sourceElement = block == null ? "" : block.element();
            this.text = block == null ? "" : block.value();
            this.links = block == null ? List.of() : List.copyOf(block.links());
            this.list = block == null ? null : block.list();
            this.table = block == null ? null : block.table();
            this.start = block == null ? 0 : block.start();
            this.members = member == null ? List.of() : List.of(member);
        }

        public String title() { return title; }
        public String articleTitle() { return label(article); }
        public String sectionPath() { return sectionPath; }
        public String sourcePosition() { return sourcePosition; }
        public WikipediaArticle.Block.Kind kind() { return kind; }
        public String text() { return text; }
        public List<WikipediaArticle.ArticleLink> links() { return links; }
        public WikipediaArticle.ArticleList list() { return list; }
        public WikipediaArticle.ArticleTable table() { return table; }
        @Override public String getIdentifier() {
            return articleTitle() + ':' + sectionPath + ':' + start;
        }
        @Override public String groupName() {
            return kind + " in " + sectionPath + ": " + title;
        }
        @Override public List<WikidataDynamicObject> members() { return members; }
        @Override public String getDisplayName() { return title; }
    }

    /** One retained list item projected with its article and section location. */
    public static final class ListItemContent extends ViewableAdapter
            implements StructuralGroup {
        @DisplayField private final String title;
        @Link private final String article;
        @Label("Section path") private final String sectionPath;
        @Label("List") private final String list;
        @Label("Source position") private final String sourcePosition;
        private final int depth;
        @Inline private final WikipediaArticle.ListItem content;
        @Hidden private final List<WikidataDynamicObject> members;
        /** The item whose nested list holds this one, as the retained hierarchy states. */
        @Hidden private final ListItemContent parent;

        ListItemContent(String article, String sectionPath, String list,
                int depth, WikipediaArticle.ListItem content) {
            this(article, sectionPath, list, depth, content, null);
        }

        ListItemContent(String article, String sectionPath, String list,
                int depth, WikipediaArticle.ListItem content,
                WikidataDynamicObject member) {
            this(article, sectionPath, list, depth, content, member, null);
        }

        ListItemContent(String article, String sectionPath, String list,
                int depth, WikipediaArticle.ListItem content,
                WikidataDynamicObject member, ListItemContent parent) {
            this.parent = parent;
            this.sourcePosition = content == null ? "List item" : content.getDisplayName();
            this.title = readableCaption(sourcePosition,
                    content == null ? "" : content.value(), false);
            this.article = linked(article);
            this.sectionPath = sectionPath == null ? "" : sectionPath;
            this.list = list == null ? "" : list;
            this.depth = Math.max(1, depth);
            this.content = content;
            this.members = member == null ? List.of() : List.of(member);
        }

        public String articleTitle() { return label(article); }
        public String sectionPath() { return sectionPath; }
        public String list() { return list; }
        public int depth() { return depth; }
        public String sourcePosition() { return sourcePosition; }
        public ListItemContent parent() { return parent; }
        public WikipediaArticle.ListItem content() { return content; }
        @Override public String getIdentifier() {
            return articleTitle() + ':' + sectionPath + ':'
                    + (content == null ? sourcePosition : content.start());
        }
        @Override public String groupName() {
            return "List item in " + sectionPath + ": " + title;
        }
        @Override public List<WikidataDynamicObject> members() { return members; }
        @Override public String getDisplayName() { return title; }
    }

    /** One retained table row projected with its article, section and table location. */
    public static final class TableRowContent extends ViewableAdapter
            implements StructuralGroup {
        @DisplayField private final String title;
        @Link private final String article;
        @Label("Section path") private final String sectionPath;
        @Label("Table") private final String table;
        @Label("Source position") private final String sourcePosition;
        @Inline private final WikipediaArticle.TableRow content;
        @Hidden private final List<WikidataDynamicObject> members;

        TableRowContent(String article, String sectionPath, String table,
                WikipediaArticle.TableRow content) {
            this(null, article, sectionPath, table, content);
        }

        TableRowContent(WikidataDynamicObject member, String article, String sectionPath,
                String table, WikipediaArticle.TableRow content) {
            this.sourcePosition = content == null ? "Table row" : content.getDisplayName();
            this.title = readableCaption(sourcePosition, tableRowText(content), false);
            this.article = linked(article);
            this.sectionPath = sectionPath == null ? "" : sectionPath;
            this.table = table == null ? "" : table;
            this.content = content;
            this.members = member == null ? List.of() : List.of(member);
        }

        public String articleTitle() { return label(article); }
        public String sectionPath() { return sectionPath; }
        public String table() { return table; }
        public String sourcePosition() { return sourcePosition; }
        public WikipediaArticle.TableRow content() { return content; }
        @Override public String getIdentifier() {
            return articleTitle() + ':' + sectionPath + ':'
                    + (content == null ? sourcePosition : content.start());
        }
        @Override public String groupName() {
            return "Table row in " + sectionPath + ": " + title;
        }
        @Override public List<WikidataDynamicObject> members() { return members; }
        @Override public String getDisplayName() { return title; }
    }

    public static final class InfoboxPattern extends ViewableAdapter
            implements StructuralGroup {
        @DisplayField @Label("Infobox key") private final String key;
        @Label("Articles") private final int articles;
        @Label("Selected articles") private final int selectedArticles;
        private final int missing;
        @Inline private final List<ValueExample> examples;
        @Hidden private final List<WikidataDynamicObject> members;

        InfoboxPattern(String key, int articles, int selectedArticles,
                List<ValueExample> examples) {
            this(key, articles, selectedArticles, examples, List.of());
        }

        InfoboxPattern(String key, int articles, int selectedArticles,
                List<ValueExample> examples, List<WikidataDynamicObject> members) {
            this.key = key;
            this.articles = articles;
            this.selectedArticles = selectedArticles;
            this.missing = Math.max(0, selectedArticles - articles);
            this.examples = List.copyOf(examples);
            this.members = List.copyOf(members);
        }

        public String key() { return key; }
        public int articles() { return articles; }
        public int selectedArticles() { return selectedArticles; }
        public List<ValueExample> examples() { return examples; }
        @Override public String groupName() { return "Infobox key: " + key; }
        @Override public List<WikidataDynamicObject> members() { return members; }
        @Override public String getIdentifier() { return key.toLowerCase(); }
        @Override public String getDisplayName() { return key; }
    }

    public static final class CategoryPattern extends ViewableAdapter
            implements StructuralGroup {
        @DisplayField private final String category;
        @Label("Articles") private final int articles;
        @Label("Selected articles") private final int selectedArticles;
        private final int missing;
        @Inline private final List<ArticleExample> examples;
        @Hidden private final List<WikidataDynamicObject> members;

        CategoryPattern(String category, int articles, int selectedArticles,
                List<ArticleExample> examples) {
            this(category, articles, selectedArticles, examples, List.of());
        }

        CategoryPattern(String category, int articles, int selectedArticles,
                List<ArticleExample> examples, List<WikidataDynamicObject> members) {
            this.category = category;
            this.articles = articles;
            this.selectedArticles = selectedArticles;
            this.missing = Math.max(0, selectedArticles - articles);
            this.examples = List.copyOf(examples);
            this.members = List.copyOf(members);
        }

        public String category() { return category; }
        public int articles() { return articles; }
        public List<ArticleExample> examples() { return examples; }
        @Override public String groupName() { return "Category: " + category; }
        @Override public List<WikidataDynamicObject> members() { return members; }
        @Override public String getIdentifier() { return category.toLowerCase(); }
        @Override public String getDisplayName() { return category; }
    }

    public static final class LinkPattern extends ViewableAdapter
            implements StructuralGroup {
        @DisplayField @Link private final String target;
        @Label("Section") private final String section;
        @Label("Articles") private final int articles;
        @Label("Selected articles") private final int selectedArticles;
        private final int missing;
        private final int occurrences;
        @Inline private final List<LinkExample> examples;
        @Hidden private final List<WikidataDynamicObject> members;

        LinkPattern(String targetTitle, String section, int articles,
                int selectedArticles, int occurrences, List<LinkExample> examples) {
            this(targetTitle, section, articles, selectedArticles, occurrences, examples,
                    List.of());
        }

        LinkPattern(String targetTitle, String section, int articles,
                int selectedArticles, int occurrences, List<LinkExample> examples,
                List<WikidataDynamicObject> members) {
            this.target = linked(targetTitle);
            this.section = section;
            this.articles = articles;
            this.selectedArticles = selectedArticles;
            this.missing = Math.max(0, selectedArticles - articles);
            this.occurrences = occurrences;
            this.examples = List.copyOf(examples);
            this.members = List.copyOf(members);
        }

        public String targetTitle() { return label(target); }
        public String section() { return section; }
        public int articles() { return articles; }
        public int occurrences() { return occurrences; }
        public List<LinkExample> examples() { return examples; }
        @Override public String groupName() {
            return "Link in " + section + ": " + targetTitle();
        }
        @Override public List<WikidataDynamicObject> members() { return members; }
        @Override public String getIdentifier() {
            return section.toLowerCase() + ':' + targetTitle().toLowerCase();
        }
        @Override public String getDisplayName() { return targetTitle(); }
    }

    /**
     * One structural HTML element kind across the selected articles, and what the article
     * structure does with it. A kind marked "Not retained" is source structure that the
     * retained article, and every recipe built on it, never sees.
     */
    public static final class BlockKindPattern extends ViewableAdapter
            implements StructuralGroup {
        @DisplayField @Label("Element") private final String element;
        @Label("Retained as") private final String retainedAs;
        @Label("Articles") private final int articles;
        @Label("Selected articles") private final int selectedArticles;
        private final int missing;
        private final int occurrences;
        @Inline private final List<BlockKindExample> examples;
        @Hidden private final List<WikidataDynamicObject> members;

        BlockKindPattern(String element, String retainedAs, int articles,
                int selectedArticles, int occurrences, List<BlockKindExample> examples) {
            this(element, retainedAs, articles, selectedArticles, occurrences, examples,
                    List.of());
        }

        BlockKindPattern(String element, String retainedAs, int articles,
                int selectedArticles, int occurrences, List<BlockKindExample> examples,
                List<WikidataDynamicObject> members) {
            this.element = element;
            this.retainedAs = retainedAs;
            this.articles = articles;
            this.selectedArticles = selectedArticles;
            this.missing = Math.max(0, selectedArticles - articles);
            this.occurrences = occurrences;
            this.examples = List.copyOf(examples);
            this.members = List.copyOf(members);
        }

        public String element() { return element; }
        public String retainedAs() { return retainedAs; }
        public int articles() { return articles; }
        public int selectedArticles() { return selectedArticles; }
        public int occurrences() { return occurrences; }
        public List<BlockKindExample> examples() { return examples; }
        @Override public String groupName() {
            return "HTML element: " + element + " — " + retainedAs;
        }
        @Override public List<WikidataDynamicObject> members() { return members; }
        @Override public String getIdentifier() { return element + ':' + retainedAs; }
        @Override public String getDisplayName() { return element; }
    }

    /** One authored table-header value across the selected articles. */
    public static final class TableHeaderPattern extends ViewableAdapter
            implements StructuralGroup {
        @DisplayField @Label("Table header") private final String header;
        @Label("Articles") private final int articles;
        @Label("Selected articles") private final int selectedArticles;
        private final int missing;
        private final int occurrences;
        @Inline private final List<TableHeaderExample> examples;
        @Hidden private final List<WikidataDynamicObject> members;

        TableHeaderPattern(String header, int articles, int selectedArticles,
                int occurrences, List<TableHeaderExample> examples,
                List<WikidataDynamicObject> members) {
            this.header = header;
            this.articles = articles;
            this.selectedArticles = selectedArticles;
            this.missing = Math.max(0, selectedArticles - articles);
            this.occurrences = occurrences;
            this.examples = List.copyOf(examples);
            this.members = List.copyOf(members);
        }

        public String header() { return header; }
        public int articles() { return articles; }
        public int occurrences() { return occurrences; }
        public List<TableHeaderExample> examples() { return examples; }
        @Override public String groupName() { return "Table header: " + header; }
        @Override public List<WikidataDynamicObject> members() { return members; }
        @Override public String getIdentifier() { return header.toLowerCase(); }
        @Override public String getDisplayName() { return header; }
    }

    public static final class TableHeaderExample extends ViewableAdapter {
        @DisplayField @Link private final String article;
        @Label("Section path") private final String sectionPath;
        @Label("Table") private final String table;

        TableHeaderExample(String title, String sectionPath, String table) {
            this.article = linked(title);
            this.sectionPath = sectionPath == null ? "" : sectionPath;
            this.table = table == null ? "" : table;
        }

        @Override public String getIdentifier() {
            return label(article) + ':' + sectionPath + ':' + table;
        }
        @Override public String getDisplayName() { return label(article); }
    }

    /** The first non-empty occurrence of one element kind in one article. */
    public static final class BlockKindExample extends ViewableAdapter {
        @DisplayField @Link private final String article;
        private final int occurrences;
        @Label("Text") private final String text;

        BlockKindExample(String title, int occurrences, String text) {
            this.article = linked(title);
            this.occurrences = occurrences;
            this.text = text == null ? "" : text;
        }

        public String title() { return label(article); }
        public int occurrences() { return occurrences; }
        public String text() { return text; }
        @Override public String getIdentifier() { return title(); }
        @Override public String getDisplayName() { return title(); }
    }

    public static final class ArticleExample extends ViewableAdapter {
        @DisplayField @Link private final String article;

        ArticleExample(String title) { article = linked(title); }
        public String title() { return label(article); }
        @Override public String getIdentifier() { return title(); }
        @Override public String getDisplayName() { return title(); }
    }

    public static final class ValueExample extends ViewableAdapter {
        @DisplayField @Link private final String article;
        @Inline private final WikipediaFragment value;

        ValueExample(String title, WikipediaFragment value) {
            article = linked(title);
            this.value = value == null ? WikipediaFragment.text("") : value;
        }
        public String title() { return label(article); }
        public WikipediaFragment value() { return value; }
        @Override public String getIdentifier() { return title() + ':' + value; }
        @Override public String getDisplayName() { return title(); }
    }

    public static final class LinkExample extends ViewableAdapter {
        @DisplayField @Link private final String article;
        @Label("Link text") private final String linkText;
        @Label("Article block") private final String block;
        @Hidden private final int start;

        LinkExample(String title, String linkText, String block, int start) {
            article = linked(title);
            this.linkText = linkText == null ? "" : linkText.trim();
            this.block = block == null ? "" : block.trim();
            this.start = start;
        }
        public String title() { return label(article); }
        public String linkText() { return linkText; }
        public String block() { return block; }
        @Override public String getIdentifier() {
            return title() + ':' + start;
        }
        @Override public String getDisplayName() { return title(); }
    }

    public static final class UnavailableArticle extends ViewableAdapter {
        @DisplayField private final String instance;
        @Label("Wikipedia article") private final String title;
        private final String problem;

        UnavailableArticle(String instance, String title, String problem) {
            this.instance = instance == null ? "" : instance.trim();
            this.title = title == null ? "" : title.trim();
            this.problem = problem == null ? "" : problem.trim();
        }
        @Override public String getIdentifier() { return instance + ':' + title; }
        @Override public String getDisplayName() { return instance; }
    }

    private static String linked(String title) {
        String clean = title == null ? "" : title.trim();
        return clean.isBlank() ? "" : clean + '|' + wikipedia.WikipediaArticleClient
                .pageUrl(clean);
    }

    private static String sectionCaption(String heading, List<SectionExample> examples) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        examples.stream().map(SectionExample::path).filter(path -> !path.isBlank())
                .forEach(paths::add);
        return paths.size() == 1 ? paths.getFirst() : heading;
    }

    private static String tableRowText(WikipediaArticle.TableRow row) {
        if (row == null) return "";
        return row.cells().stream().map(WikipediaArticle.TableCell::value)
                .filter(value -> value != null && !value.isBlank())
                .limit(4).collect(java.util.stream.Collectors.joining(" | "));
    }

    /** Whether the retained block's title is one the page authored: a definition term, or
     *  a table's caption. Every other block title is a structural position. */
    private static boolean authoredTitle(WikipediaArticle.Block block) {
        return block != null && (block.kind() == WikipediaArticle.Block.Kind.DEFINITION
                || block.table() != null && !block.table().caption().isBlank());
    }

    /** Parser coordinates remain provenance; authored source text names anonymous rows. */
    private static String readableCaption(
            String sourcePosition, String sourceText, boolean authored) {
        String position = compact(sourcePosition);
        String text = compact(sourceText);
        if (authored || text.isBlank()) {
            return position.isBlank() ? "Structural element" : position;
        }
        if (text.length() <= CAPTION_LIMIT) return text;
        return text.substring(0, CAPTION_LIMIT - 1).stripTrailing() + '…';
    }

    private static String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String label(String linked) {
        if (linked == null) return "";
        int separator = linked.indexOf('|');
        return (separator < 0 ? linked : linked.substring(0, separator)).trim();
    }
}
