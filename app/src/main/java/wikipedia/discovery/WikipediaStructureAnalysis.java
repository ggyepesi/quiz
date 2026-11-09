package wikipedia.discovery;

import datasource.evidence.InfoboxParameters;
import datasource.evidence.WikipediaArticle;
import quiz.source.WikipediaSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static wikipedia.discovery.WikipediaDiscoveryRows.*;

/** Pure comparison of retained Wikipedia source structure; it infers no domain fact. */
public final class WikipediaStructureAnalysis {
    private WikipediaStructureAnalysis() {}

    public static Result analyze(List<WikipediaDiscoveryQuery.Article> selected) {
        List<WikipediaDiscoveryQuery.Article> input = selected == null
                ? List.of() : List.copyOf(selected);
        int selectedCount = input.size();
        List<WikipediaDiscoveryQuery.Article> available = input.stream()
                .filter(WikipediaDiscoveryQuery.Article::available).toList();

        Map<String, SectionAccumulator> sections = new LinkedHashMap<>();
        Map<String, CategoryAccumulator> categories = new LinkedHashMap<>();
        Map<String, InfoboxAccumulator> infoboxes = new LinkedHashMap<>();
        Map<String, LinkAccumulator> links = new LinkedHashMap<>();
        Map<String, BlockKindAccumulator> blockKinds = new LinkedHashMap<>();
        boolean blockKindsAvailable = !available.isEmpty()
                && available.stream().allMatch(value -> value.elements() != null);
        List<WikipediaSource> articles = new ArrayList<>();

        for (WikipediaDiscoveryQuery.Article source : available) {
            articles.add(new WikipediaSource(source.title(), source.article()));
            for (WikipediaArticle.Section section : source.article().sections()) {
                collectSection(source.title(), section, "", sections, links);
            }
            for (String category : new LinkedHashSet<>(source.article().categories())) {
                String key = normalized(category);
                if (key.isBlank()) continue;
                categories.computeIfAbsent(key,
                        ignored -> new CategoryAccumulator(category))
                        .add(source.title());
            }
            collectInfobox(source.title(), source.infobox(), infoboxes);
            if (blockKindsAvailable) {
                for (wikipedia.ArticleElementInventory.ElementKind kind : source.elements()) {
                    blockKinds.computeIfAbsent(kind.element() + '\0' + kind.retainedAs(),
                            ignored -> new BlockKindAccumulator(
                                    kind.element(), kind.retainedAs()))
                            .add(source.title(), kind);
                }
            }
        }

        List<UnavailableArticle> unavailable = input.stream()
                .filter(value -> !value.available() || !value.problem().isBlank())
                .map(value -> new UnavailableArticle(value.instance().getDisplayName(),
                        value.title(), value.problem().isBlank()
                                ? "Structured article unavailable." : value.problem()))
                .toList();

        return new Result(
                List.copyOf(articles),
                ranked(sections.values().stream()
                        .map(value -> value.row(selectedCount)).toList(),
                        SectionPattern::articles, SectionPattern::heading),
                ranked(infoboxes.values().stream()
                        .map(value -> value.row(selectedCount)).toList(),
                        InfoboxPattern::articles, InfoboxPattern::key),
                ranked(categories.values().stream()
                        .map(value -> value.row(selectedCount)).toList(),
                        CategoryPattern::articles, CategoryPattern::category),
                ranked(links.values().stream()
                        .map(value -> value.row(selectedCount)).toList(),
                        LinkPattern::articles,
                        value -> value.section() + " " + value.targetTitle()),
                ranked(blockKinds.values().stream()
                        .map(value -> value.row(selectedCount)).toList(),
                        BlockKindPattern::articles,
                        value -> value.element() + " " + value.retainedAs()),
                blockKindsAvailable, unavailable, selectedCount, available.size());
    }

    private static void collectSection(String article, WikipediaArticle.Section section,
            String parent, Map<String, SectionAccumulator> sections,
            Map<String, LinkAccumulator> links) {
        String path = parent.isBlank() ? section.heading()
                : parent + " › " + section.heading();
        String key = normalized(section.heading());
        if (!key.isBlank()) {
            sections.computeIfAbsent(key,
                    ignored -> new SectionAccumulator(section.heading()))
                    .add(article, path, section);
        }
        for (WikipediaArticle.Block block : section.blocks()) {
            for (WikipediaArticle.ArticleLink link : block.links()) {
                String linkKey = key + '\0' + normalized(link.targetTitle());
                links.computeIfAbsent(linkKey, ignored ->
                        new LinkAccumulator(section.heading(), link.targetTitle()))
                        .add(article, link.text(), block.value(), link.start());
            }
        }
        for (WikipediaArticle.Section child : section.sections()) {
            collectSection(article, child, path, sections, links);
        }
    }

    private static void collectInfobox(String article, InfoboxParameters infobox,
            Map<String, InfoboxAccumulator> values) {
        if (infobox == null) return;
        infobox.values().forEach((key, value) -> {
            String name = key.toString();
            values.computeIfAbsent(normalized(name),
                    ignored -> new InfoboxAccumulator(name)).add(article, value);
        });
    }

    private static <T> List<T> ranked(List<T> values,
            java.util.function.ToIntFunction<T> coverage,
            java.util.function.Function<T, String> name) {
        return values.stream().sorted(Comparator
                .comparingInt(coverage).reversed()
                .thenComparing(name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record Result(
            List<WikipediaSource> articles,
            List<SectionPattern> sections,
            List<InfoboxPattern> infoboxes,
            List<CategoryPattern> categories,
            List<LinkPattern> links,
            List<BlockKindPattern> blockKinds,
            boolean blockKindsAvailable,
            List<UnavailableArticle> unavailable,
            int selectedArticles,
            int availableArticles) {
        public Result {
            articles = List.copyOf(articles);
            sections = List.copyOf(sections);
            infoboxes = List.copyOf(infoboxes);
            categories = List.copyOf(categories);
            links = List.copyOf(links);
            blockKinds = List.copyOf(blockKinds);
            unavailable = List.copyOf(unavailable);
        }
    }

    private static final class SectionAccumulator {
        private final String heading;
        private final LinkedHashSet<String> articles = new LinkedHashSet<>();
        private final LinkedHashSet<Integer> levels = new LinkedHashSet<>();
        private final List<SectionExample> examples = new ArrayList<>();

        private SectionAccumulator(String heading) { this.heading = heading; }
        private void add(String article, String path, WikipediaArticle.Section section) {
            articles.add(article);
            levels.add(section.level());
            examples.add(new SectionExample(article, path, section.level(), section.blocks(),
                    section.start()));
        }
        private SectionPattern row(int selected) {
            return new SectionPattern(heading, articles.size(), selected, examples.size(),
                    levels.stream().map(String::valueOf)
                            .collect(java.util.stream.Collectors.joining(", ")),
                    examples);
        }
    }

    private static final class BlockKindAccumulator {
        private final String element;
        private final String retainedAs;
        private final LinkedHashSet<String> articles = new LinkedHashSet<>();
        private final List<BlockKindExample> examples = new ArrayList<>();
        private int occurrences;

        private BlockKindAccumulator(String element, String retainedAs) {
            this.element = element;
            this.retainedAs = retainedAs;
        }
        private void add(String article, wikipedia.ArticleElementInventory.ElementKind kind) {
            articles.add(article);
            occurrences += kind.occurrences();
            examples.add(new BlockKindExample(article, kind.occurrences(), kind.example()));
        }
        private BlockKindPattern row(int selected) {
            return new BlockKindPattern(element, retainedAs, articles.size(), selected,
                    occurrences, examples);
        }
    }

    private static final class CategoryAccumulator {
        private final String category;
        private final LinkedHashSet<String> articles = new LinkedHashSet<>();

        private CategoryAccumulator(String category) { this.category = category; }
        private void add(String article) { articles.add(article); }
        private CategoryPattern row(int selected) {
            return new CategoryPattern(category, articles.size(), selected,
                    articles.stream().map(ArticleExample::new).toList());
        }
    }

    private static final class InfoboxAccumulator {
        private final String key;
        private final LinkedHashSet<String> articles = new LinkedHashSet<>();
        private final List<ValueExample> examples = new ArrayList<>();

        private InfoboxAccumulator(String key) { this.key = key; }
        private void add(String article, String value) {
            articles.add(article);
            examples.add(new ValueExample(article, value));
        }
        private InfoboxPattern row(int selected) {
            return new InfoboxPattern(key, articles.size(), selected, examples);
        }
    }

    private static final class LinkAccumulator {
        private final String section;
        private final String target;
        private final LinkedHashSet<String> articles = new LinkedHashSet<>();
        private final List<LinkExample> examples = new ArrayList<>();

        private LinkAccumulator(String section, String target) {
            this.section = section;
            this.target = target;
        }
        private void add(String article, String text, String block, int start) {
            articles.add(article);
            examples.add(new LinkExample(article, text, block, start));
        }
        private LinkPattern row(int selected) {
            return new LinkPattern(target, section, articles.size(), selected,
                    examples.size(), examples);
        }
    }
}
