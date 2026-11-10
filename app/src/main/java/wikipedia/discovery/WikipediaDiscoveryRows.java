package wikipedia.discovery;

import datasource.evidence.WikipediaArticle;
import datasource.evidence.WikipediaFragment;
import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.annotations.Inline;
import objectview.annotations.Hidden;
import objectview.annotations.Label;
import objectview.annotations.Link;

import java.util.List;

/** ObjectView rows used by the Wikipedia discovery workbench. */
public final class WikipediaDiscoveryRows {
    private WikipediaDiscoveryRows() {}

    public static final class SectionPattern extends ViewableAdapter {
        @DisplayField private final String heading;
        @Label("Articles") private final int articles;
        @Label("Selected articles") private final int selectedArticles;
        private final int missing;
        private final int occurrences;
        private final String levels;
        @Inline private final List<SectionExample> examples;

        SectionPattern(String heading, int articles, int selectedArticles,
                int occurrences, String levels, List<SectionExample> examples) {
            this.heading = heading;
            this.articles = articles;
            this.selectedArticles = selectedArticles;
            this.missing = Math.max(0, selectedArticles - articles);
            this.occurrences = occurrences;
            this.levels = levels;
            this.examples = List.copyOf(examples);
        }

        public String heading() { return heading; }
        public int articles() { return articles; }
        public int selectedArticles() { return selectedArticles; }
        public int occurrences() { return occurrences; }
        public List<SectionExample> examples() { return examples; }
        @Override public String getIdentifier() { return heading.toLowerCase(); }
        @Override public String getDisplayName() { return heading; }
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

    public static final class InfoboxPattern extends ViewableAdapter {
        @DisplayField @Label("Infobox key") private final String key;
        @Label("Articles") private final int articles;
        @Label("Selected articles") private final int selectedArticles;
        private final int missing;
        @Inline private final List<ValueExample> examples;

        InfoboxPattern(String key, int articles, int selectedArticles,
                List<ValueExample> examples) {
            this.key = key;
            this.articles = articles;
            this.selectedArticles = selectedArticles;
            this.missing = Math.max(0, selectedArticles - articles);
            this.examples = List.copyOf(examples);
        }

        public String key() { return key; }
        public int articles() { return articles; }
        public int selectedArticles() { return selectedArticles; }
        public List<ValueExample> examples() { return examples; }
        @Override public String getIdentifier() { return key.toLowerCase(); }
        @Override public String getDisplayName() { return key; }
    }

    public static final class CategoryPattern extends ViewableAdapter {
        @DisplayField private final String category;
        @Label("Articles") private final int articles;
        @Label("Selected articles") private final int selectedArticles;
        private final int missing;
        @Inline private final List<ArticleExample> examples;

        CategoryPattern(String category, int articles, int selectedArticles,
                List<ArticleExample> examples) {
            this.category = category;
            this.articles = articles;
            this.selectedArticles = selectedArticles;
            this.missing = Math.max(0, selectedArticles - articles);
            this.examples = List.copyOf(examples);
        }

        public String category() { return category; }
        public int articles() { return articles; }
        public List<ArticleExample> examples() { return examples; }
        @Override public String getIdentifier() { return category.toLowerCase(); }
        @Override public String getDisplayName() { return category; }
    }

    public static final class LinkPattern extends ViewableAdapter {
        @DisplayField @Link private final String target;
        @Label("Section") private final String section;
        @Label("Articles") private final int articles;
        @Label("Selected articles") private final int selectedArticles;
        private final int missing;
        private final int occurrences;
        @Inline private final List<LinkExample> examples;

        LinkPattern(String targetTitle, String section, int articles,
                int selectedArticles, int occurrences, List<LinkExample> examples) {
            this.target = linked(targetTitle);
            this.section = section;
            this.articles = articles;
            this.selectedArticles = selectedArticles;
            this.missing = Math.max(0, selectedArticles - articles);
            this.occurrences = occurrences;
            this.examples = List.copyOf(examples);
        }

        public String targetTitle() { return label(target); }
        public String section() { return section; }
        public int articles() { return articles; }
        public int occurrences() { return occurrences; }
        public List<LinkExample> examples() { return examples; }
        @Override public String getIdentifier() {
            return section.toLowerCase() + ':' + targetTitle().toLowerCase();
        }
        @Override public String getDisplayName() { return targetTitle(); }
    }

    /**
     * One block-level element kind across the selected articles, and what the article
     * structure does with it. A kind marked "Not retained" is source structure that the
     * retained article, and every recipe built on it, never sees.
     */
    public static final class BlockKindPattern extends ViewableAdapter {
        @DisplayField @Label("Element") private final String element;
        @Label("Retained as") private final String retainedAs;
        @Label("Articles") private final int articles;
        @Label("Selected articles") private final int selectedArticles;
        private final int missing;
        private final int occurrences;
        @Inline private final List<BlockKindExample> examples;

        BlockKindPattern(String element, String retainedAs, int articles,
                int selectedArticles, int occurrences, List<BlockKindExample> examples) {
            this.element = element;
            this.retainedAs = retainedAs;
            this.articles = articles;
            this.selectedArticles = selectedArticles;
            this.missing = Math.max(0, selectedArticles - articles);
            this.occurrences = occurrences;
            this.examples = List.copyOf(examples);
        }

        public String element() { return element; }
        public String retainedAs() { return retainedAs; }
        public int articles() { return articles; }
        public int selectedArticles() { return selectedArticles; }
        public int occurrences() { return occurrences; }
        public List<BlockKindExample> examples() { return examples; }
        @Override public String getIdentifier() { return element + ':' + retainedAs; }
        @Override public String getDisplayName() { return element; }
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

    private static String label(String linked) {
        if (linked == null) return "";
        int separator = linked.indexOf('|');
        return (separator < 0 ? linked : linked.substring(0, separator)).trim();
    }
}
