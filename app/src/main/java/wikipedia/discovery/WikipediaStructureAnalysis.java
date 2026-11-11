package wikipedia.discovery;

import datasource.evidence.InfoboxParameters;
import datasource.evidence.WikipediaArticle;
import quiz.source.WikipediaSource;
import wikidata.explore.extract.WikidataDynamicObject;

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
        Map<String, TableHeaderAccumulator> tableHeaders = new LinkedHashMap<>();
        List<SectionContent> contents = new ArrayList<>();
        List<ListItemContent> listItems = new ArrayList<>();
        List<TableRowContent> tableRows = new ArrayList<>();
        boolean blockKindsAvailable = !available.isEmpty()
                && available.stream().allMatch(value -> value.elements() != null);
        List<WikipediaSource> articles = new ArrayList<>();

        for (WikipediaDiscoveryQuery.Article source : available) {
            articles.add(new WikipediaSource(source.title(), source.article()));
            for (WikipediaArticle.Section section : source.article().sections()) {
                collectSection(source.instance(), source.title(), section, "", sections,
                        contents, listItems, tableRows, tableHeaders, links);
            }
            for (String category : new LinkedHashSet<>(source.article().categories())) {
                String key = normalized(category);
                if (key.isBlank()) continue;
                categories.computeIfAbsent(key,
                        ignored -> new CategoryAccumulator(category))
                        .add(source.instance(), source.title());
            }
            collectInfobox(source.instance(), source.title(), source.infobox(), infoboxes);
            if (blockKindsAvailable) {
                for (wikipedia.ArticleElementInventory.ElementKind kind : source.elements()) {
                    blockKinds.computeIfAbsent(kind.element() + '\0' + kind.retainedAs(),
                            ignored -> new BlockKindAccumulator(
                                    kind.element(), kind.retainedAs()))
                            .add(source.instance(), source.title(), kind);
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
                List.copyOf(contents),
                List.copyOf(listItems),
                List.copyOf(tableRows),
                ranked(tableHeaders.values().stream()
                                .map(value -> value.row(selectedCount)).toList(),
                        TableHeaderPattern::articles, TableHeaderPattern::header),
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

    private static void collectSection(WikidataDynamicObject instance, String article,
            WikipediaArticle.Section section,
            String parent, Map<String, SectionAccumulator> sections,
            List<SectionContent> contents,
            List<ListItemContent> listItems,
            List<TableRowContent> tableRows,
            Map<String, TableHeaderAccumulator> tableHeaders,
            Map<String, LinkAccumulator> links) {
        String path = parent.isBlank() ? section.heading()
                : parent + " › " + section.heading();
        String key = normalized(section.heading());
        if (!key.isBlank()) {
            sections.computeIfAbsent(key,
                    ignored -> new SectionAccumulator(section.heading()))
                    .add(instance, article, path, section);
        }
        for (WikipediaArticle.Block block : section.blocks()) {
            contents.add(new SectionContent(article, path, block));
            if (block.list() != null) {
                collectListItems(article, path, block.list(), block.list().items(),
                        1, listItems);
            }
            if (block.table() != null) {
                for (WikipediaArticle.TableRow row : block.table().rows()) {
                    tableRows.add(new TableRowContent(article, path,
                            block.table().getDisplayName(), row));
                    for (WikipediaArticle.TableCell cell : row.cells()) {
                        if (!cell.header() || cell.value().isBlank()) continue;
                        String headerKey = normalized(cell.value());
                        tableHeaders.computeIfAbsent(headerKey,
                                ignored -> new TableHeaderAccumulator(cell.value()))
                                .add(instance, article, path,
                                        block.table().getDisplayName());
                    }
                }
            }
            for (WikipediaArticle.ArticleLink link : block.links()) {
                String linkKey = key + '\0' + normalized(link.targetTitle());
                links.computeIfAbsent(linkKey, ignored ->
                        new LinkAccumulator(section.heading(), link.targetTitle()))
                        .add(instance, article, link.text(), block.value(), link.start());
            }
        }
        for (WikipediaArticle.Section child : section.sections()) {
            collectSection(instance, article, child, path, sections, contents,
                    listItems, tableRows, tableHeaders, links);
        }
    }

    private static void collectListItems(String article, String path,
            WikipediaArticle.ArticleList list, List<WikipediaArticle.ListItem> items,
            int depth, List<ListItemContent> into) {
        for (WikipediaArticle.ListItem item : items) {
            into.add(new ListItemContent(article, path, list.getDisplayName(), depth, item));
            for (WikipediaArticle.ArticleList nested : item.lists()) {
                collectListItems(article, path, nested, nested.items(), depth + 1, into);
            }
        }
    }

    private static void collectInfobox(WikidataDynamicObject instance, String article,
            InfoboxParameters infobox,
            Map<String, InfoboxAccumulator> values) {
        if (infobox == null) return;
        infobox.structuredValues().forEach((key, value) -> {
            String name = key.toString();
            values.computeIfAbsent(normalized(name),
                    ignored -> new InfoboxAccumulator(name)).add(instance, article, value);
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
            List<SectionContent> contents,
            List<ListItemContent> listItems,
            List<TableRowContent> tableRows,
            List<TableHeaderPattern> tableHeaders,
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
            contents = List.copyOf(contents);
            listItems = List.copyOf(listItems);
            tableRows = List.copyOf(tableRows);
            tableHeaders = List.copyOf(tableHeaders);
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
        private final LinkedHashSet<WikidataDynamicObject> members = new LinkedHashSet<>();

        private SectionAccumulator(String heading) { this.heading = heading; }
        private void add(WikidataDynamicObject instance, String article, String path,
                WikipediaArticle.Section section) {
            articles.add(article);
            members.add(instance);
            levels.add(section.level());
            examples.add(new SectionExample(article, path, section.level(), section.blocks(),
                    section.start()));
        }
        private SectionPattern row(int selected) {
            return new SectionPattern(heading, articles.size(), selected, examples.size(),
                    levels.stream().map(String::valueOf)
                            .collect(java.util.stream.Collectors.joining(", ")),
                    examples, List.copyOf(members));
        }
    }

    private static final class BlockKindAccumulator {
        private final String element;
        private final String retainedAs;
        private final LinkedHashSet<String> articles = new LinkedHashSet<>();
        private final List<BlockKindExample> examples = new ArrayList<>();
        private final LinkedHashSet<WikidataDynamicObject> members = new LinkedHashSet<>();
        private int occurrences;

        private BlockKindAccumulator(String element, String retainedAs) {
            this.element = element;
            this.retainedAs = retainedAs;
        }
        private void add(WikidataDynamicObject instance, String article,
                wikipedia.ArticleElementInventory.ElementKind kind) {
            articles.add(article);
            members.add(instance);
            occurrences += kind.occurrences();
            examples.add(new BlockKindExample(article, kind.occurrences(), kind.example()));
        }
        private BlockKindPattern row(int selected) {
            return new BlockKindPattern(element, retainedAs, articles.size(), selected,
                    occurrences, examples, List.copyOf(members));
        }
    }

    private static final class CategoryAccumulator {
        private final String category;
        private final LinkedHashSet<String> articles = new LinkedHashSet<>();
        private final LinkedHashSet<WikidataDynamicObject> members = new LinkedHashSet<>();

        private CategoryAccumulator(String category) { this.category = category; }
        private void add(WikidataDynamicObject instance, String article) {
            articles.add(article);
            members.add(instance);
        }
        private CategoryPattern row(int selected) {
            return new CategoryPattern(category, articles.size(), selected,
                    articles.stream().map(ArticleExample::new).toList(),
                    List.copyOf(members));
        }
    }

    private static final class InfoboxAccumulator {
        private final String key;
        private final LinkedHashSet<String> articles = new LinkedHashSet<>();
        private final List<ValueExample> examples = new ArrayList<>();
        private final LinkedHashSet<WikidataDynamicObject> members = new LinkedHashSet<>();

        private InfoboxAccumulator(String key) { this.key = key; }
        private void add(WikidataDynamicObject instance, String article,
                datasource.evidence.WikipediaFragment value) {
            articles.add(article);
            members.add(instance);
            examples.add(new ValueExample(article, value));
        }
        private InfoboxPattern row(int selected) {
            return new InfoboxPattern(key, articles.size(), selected, examples,
                    List.copyOf(members));
        }
    }

    private static final class LinkAccumulator {
        private final String section;
        private final String target;
        private final LinkedHashSet<String> articles = new LinkedHashSet<>();
        private final List<LinkExample> examples = new ArrayList<>();
        private final LinkedHashSet<WikidataDynamicObject> members = new LinkedHashSet<>();

        private LinkAccumulator(String section, String target) {
            this.section = section;
            this.target = target;
        }
        private void add(WikidataDynamicObject instance, String article,
                String text, String block, int start) {
            articles.add(article);
            members.add(instance);
            examples.add(new LinkExample(article, text, block, start));
        }
        private LinkPattern row(int selected) {
            return new LinkPattern(target, section, articles.size(), selected,
                    examples.size(), examples, List.copyOf(members));
        }
    }

    private static final class TableHeaderAccumulator {
        private final String header;
        private final LinkedHashSet<String> articles = new LinkedHashSet<>();
        private final LinkedHashSet<WikidataDynamicObject> members = new LinkedHashSet<>();
        private final List<TableHeaderExample> examples = new ArrayList<>();

        private TableHeaderAccumulator(String header) { this.header = header; }
        private void add(WikidataDynamicObject instance, String article,
                String sectionPath, String table) {
            articles.add(article);
            members.add(instance);
            examples.add(new TableHeaderExample(article, sectionPath, table));
        }
        private TableHeaderPattern row(int selected) {
            return new TableHeaderPattern(header, articles.size(), selected,
                    examples.size(), examples, List.copyOf(members));
        }
    }
}
