package datasource.wikipedia;

import datasource.EntityRef;
import datasource.api.BindingScope;
import datasource.api.ParameterDescriptor;
import datasource.api.SourceRecipe;
import datasource.api.SourceValueKind;
import datasource.api.SourceValueSchema;
import datasource.api.acquisition.AcquiredPopulation;
import datasource.api.acquisition.AcquiringClassPopulationOperation;
import datasource.api.acquisition.PopulationAcquisition;
import wikidata.WikidataIds;
import wikidata.explore.wikiproject.WikiProjectArticle;
import wikidata.explore.wikiproject.WikiProjectCategoryReader;
import wikidata.explore.wikiproject.WikiProjectMediaWikiClient;
import work.Query;
import work.QueryContext;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Direct article members of one bounded English-Wikipedia category. */
public final class WikipediaCategoryPopulationOperation
        implements AcquiringClassPopulationOperation {

    public static final String ID = "category-members";
    public static final String CATEGORY = "category";
    public static final String LIMIT = "limit";
    public static final int DEFAULT_LIMIT = 200;
    public static final int MAX_LIMIT = 5_000;

    private final Supplier<WikiProjectCategoryReader> readers;

    public WikipediaCategoryPopulationOperation() {
        this(() -> new WikiProjectCategoryReader(new WikiProjectMediaWikiClient()));
    }

    WikipediaCategoryPopulationOperation(Supplier<WikiProjectCategoryReader> readers) {
        this.readers = readers == null
                ? () -> new WikiProjectCategoryReader(new WikiProjectMediaWikiClient())
                : readers;
    }

    @Override public String id() { return ID; }

    @Override public String displayName() { return "Direct category members"; }

    @Override public BindingScope scope() { return BindingScope.CLASS_POPULATION; }

    @Override public List<ParameterDescriptor> parameters() {
        return List.of(
                new ParameterDescriptor(CATEGORY, "Wikipedia category",
                        ParameterDescriptor.Kind.TEXT, true, "", List.of(),
                        "The English-Wikipedia category whose direct article members "
                                + "produce this class. Category: is optional."),
                new ParameterDescriptor(LIMIT, "Maximum articles",
                        ParameterDescriptor.Kind.INTEGER, true,
                        String.valueOf(DEFAULT_LIMIT), List.of(),
                        "A hard bound on direct article members. Subcategories are not "
                                + "followed."));
    }

    @Override public SourceValueSchema outputSchema() {
        return SourceValueSchema.collection(SourceValueKind.ENTITY_REFERENCE,
                EntityRef.WIKIDATA);
    }

    @Override public PopulationAcquisition acquisition(SourceRecipe recipe) {
        String category = category(recipe);
        int limit = limit(recipe);
        return new PopulationAcquisition(EntityRef.WIKIDATA,
                displayCategory(category), new CategoryMembersQuery(
                        category, limit, readers));
    }

    public static String category(SourceRecipe recipe) {
        String value = recipe == null ? "" : recipe.parameter(CATEGORY).trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("A Wikipedia category is required");
        }
        return value.regionMatches(true, 0, "Category:", 0, 9)
                ? "Category:" + value.substring(9).trim()
                : "Category:" + value;
    }

    public static int limit(SourceRecipe recipe) {
        String text = recipe == null ? "" : recipe.parameter(LIMIT).trim();
        if (text.isBlank()) text = String.valueOf(DEFAULT_LIMIT);
        try {
            int value = Integer.parseInt(text);
            if (value < 1 || value > MAX_LIMIT) {
                throw new IllegalArgumentException("Maximum articles must be between 1 and "
                        + MAX_LIMIT);
            }
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Maximum articles must be a number", invalid);
        }
    }

    public static String displayCategory(String category) {
        String clean = category == null ? "" : category.trim();
        return clean.regionMatches(true, 0, "Category:", 0, 9)
                ? clean.substring(9).trim() : clean;
    }

    static AcquiredPopulation result(List<WikiProjectArticle> articles) {
        LinkedHashSet<String> qids = new LinkedHashSet<>();
        java.util.ArrayList<String> unresolved = new java.util.ArrayList<>();
        for (WikiProjectArticle article : articles == null
                ? List.<WikiProjectArticle>of() : articles) {
            if (article == null) continue;
            String qid = article.qid() == null ? "" : article.qid().trim().toUpperCase();
            if (WikidataIds.isQid(qid)) {
                qids.add(qid);
            } else if (!article.title().isBlank()) {
                unresolved.add(article.title());
            }
        }
        return new AcquiredPopulation(
                qids.stream().map(EntityRef::wikidata).toList(), unresolved);
    }

    private record CategoryMembersQuery(
            String category,
            int limit,
            Supplier<WikiProjectCategoryReader> readers)
            implements Query<AcquiredPopulation> {

        @Override public String purpose() {
            return "Load Wikipedia category population";
        }

        @Override public String skeleton() {
            return "direct article members -> Wikidata QIDs";
        }

        @Override public String queryType() { return "MediaWiki API"; }

        @Override public Map<String, String> parameters() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("category", category);
            values.put("maximum articles", String.valueOf(limit));
            values.put("subcategories", "not followed");
            return values;
        }

        @Override public AcquiredPopulation execute(QueryContext context) throws Exception {
            context.cancellation().throwIfCancelled();
            return context.step(category, queryType(), skeleton(), parameters(), step -> {
                WikiProjectCategoryReader reader = readers.get();
                wikidata.explore.extract.GenerationLog log =
                        wikidata.explore.query.logical.StepGenerationLog.of(context, step);
                Map<String, WikiProjectArticle> byTitle = new LinkedHashMap<>();
                String continuation = "";
                int pages = 0;
                while (byTitle.size() < limit) {
                    context.cancellation().throwIfCancelled();
                    int pageNumber = pages + 1;
                    int remaining = limit - byTitle.size();
                    String pageContinuation = continuation;
                    java.util.concurrent.atomic.AtomicReference<
                            WikiProjectCategoryReader.CategoryMemberPage> read =
                            new java.util.concurrent.atomic.AtomicReference<>();
                    batch.WorkUnit<WikiProjectCategoryReader.CategoryMemberPage> unit =
                            page(reader, category, remaining, pageNumber, pageContinuation);
                    new batch.BatchExecutor<WikiProjectCategoryReader.CategoryMemberPage>(
                            batch.BatchPolicy.defaults().withResume(false)
                                    .withMaxSplitDepth(0),
                            log.batchProgress(), batch.FailureClassifier.standard(),
                            context.cancellation(), batch.BatchCheckpointStore.NONE, 1)
                            .run(List.of(unit), (ignored, value) -> read.set(value));
                    WikiProjectCategoryReader.CategoryMemberPage page =
                            java.util.Objects.requireNonNull(read.get(),
                                    "Category page completed without a result");
                    pages++;
                    for (WikiProjectArticle article : page.articles()) {
                        if (article != null && !article.title().isBlank()) {
                            byTitle.putIfAbsent(article.title(), article);
                        }
                        if (byTitle.size() >= limit) break;
                    }
                    continuation = page.continuation();
                    if (continuation.isBlank()) break;
                }
                List<WikiProjectArticle> articles = byTitle.values().stream()
                        .sorted(java.util.Comparator.comparing(
                                WikiProjectArticle::title,
                                String.CASE_INSENSITIVE_ORDER))
                        .toList();
                AcquiredPopulation result = result(articles);
                String bound = articles.size() >= limit
                        ? "; configured limit reached" : "";
                step.summary(pages + " page(s), " + articles.size() + " direct article(s), "
                        + result.members().size() + " resolved QID(s), "
                        + result.unresolvedSourceRecords().size()
                        + " article(s) without a Wikidata QID" + bound);
                return result;
            });
        }

        private static batch.WorkUnit<WikiProjectCategoryReader.CategoryMemberPage> page(
                WikiProjectCategoryReader reader, String category, int remaining,
                int pageNumber, String continuation) {
            String request = reader.categoryMemberRequestUrl(
                    category, remaining, 0, continuation);
            batch.WorkDescriptor descriptor = new batch.WorkDescriptor(
                    "wikipedia-category-page",
                    "category-page-" + pageNumber,
                    "Wikipedia category page " + pageNumber,
                    Map.of("category", category,
                            "continuation", continuation == null ? "" : continuation));
            return new batch.WorkUnit<>() {
                @Override public batch.WorkDescriptor descriptor() { return descriptor; }
                @Override public String request() { return request; }
                @Override public WikiProjectCategoryReader.CategoryMemberPage execute()
                        throws Exception {
                    return reader.categoryMemberPage(
                            category, remaining, 0, continuation);
                }
                @Override public List<? extends batch.WorkUnit<
                        WikiProjectCategoryReader.CategoryMemberPage>> split() {
                    return List.of();
                }
            };
        }

        @Override public int rowCount(AcquiredPopulation result) {
            return result == null ? 0 : result.members().size();
        }

        @Override public String summary(AcquiredPopulation result) {
            return result == null ? "0 resolved QIDs"
                    : result.members().size() + " resolved QID(s), "
                            + result.unresolvedSourceRecords().size() + " unresolved article(s)";
        }
    }
}
