package wikidata.explore.query.logical;

import quiz.enrichment.WikimediaEntityLookup;
import wikidata.WikidataBinding;
import wikidata.WikidataIds;
import wikidata.explore.query.core.Datasource;
import work.Query;
import work.QueryContext;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.query.template.sparql.SparqlQueries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import wikidata.explore.query.core.WikidataAccess;

/** Discovers observed English Wikipedia categories for supplied or sampled entities.
 * It does not infer a relation pattern; the user chooses an observed category and marks
 * its variable part explicitly. */
public final class DiscoverWikipediaCategoriesQuery implements Query<TableQueryResult> {
    private final List<String> qids;
    private final String typeQid;
    private final int sampleSize;

    public DiscoverWikipediaCategoriesQuery(List<String> qids) {
        this.qids = qids == null ? List.of() : qids.stream()
                .filter(WikidataIds::isQid).distinct().limit(12).toList();
        this.typeQid = "";
        this.sampleSize = 0;
    }

    public DiscoverWikipediaCategoriesQuery(String typeQid, int sampleSize) {
        this.qids = List.of();
        this.typeQid = typeQid == null ? "" : typeQid.trim();
        this.sampleSize = Math.max(1, Math.min(12, sampleSize));
    }

    /** How many articles this discovery will read. The seed rule lives HERE, so a
     *  caller wording its explanation cannot describe a different sample than the one
     *  that runs — it was recomputing the filter and would have drifted from it. */
    public int seedCount() {
        return qids.isEmpty() ? sampleSize : qids.size();
    }

    public boolean singleArticle() { return seedCount() == 1; }

    @Override public String purpose() { return "Discover Wikipedia categories"; }
    @Override public String skeleton() {
        return "selected/sample entities -> English Wikipedia sitelinks -> observed categories";
    }
    @Override public String queryType() { return "Wikipedia API"; }
    @Override public String description() {
        return "List category titles without guessing a field relation";
    }
    @Override public Map<String, String> parameters() {
        return Map.of("entities", Integer.toString(qids.size()),
                "typeQid", typeQid, "sampleSize", Integer.toString(sampleSize));
    }

    @Override public TableQueryResult execute(QueryContext context) throws Exception {
        List<String> subjects = qids.isEmpty() ? sample(context) : qids;
        // One request for every seed, not one per seed: the reader is waiting on this.
        Map<String, WikimediaEntityLookup.EntityRecord> entities =
                new WikimediaEntityLookup().byQids(subjects).execute(context);
        wikipedia.WikipediaArticleClient articles = new wikipedia.WikipediaArticleClient();
        Map<String, Seen> seen = new LinkedHashMap<>();
        for (String qid : subjects) {
            WikimediaEntityLookup.EntityRecord entity = entities.get(qid);
            if (entity == null) continue;
            String title = entity.sitelink("enwiki");
            if (title == null || title.isBlank()) continue;
            wikipedia.WikipediaArticleClient.Article article =
                    articles.byTitle(title).execute(context);
            if (article == null) continue;
            for (String category : new LinkedHashSet<>(article.categories())) {
                seen.computeIfAbsent(category, ignored -> new Seen()).articles.add(article.title());
            }
        }
        List<List<Object>> rows = new ArrayList<>();
        seen.forEach((category, value) -> rows.add(List.of(
                category, value.articles.size(), String.join(", ", value.articles.stream()
                        .limit(3).toList()))));
        rankByDistinctiveness(rows);
        return new TableQueryResult(List.of("Category", "Have", "Examples"), rows);
    }

    /**
     * Least-shared first.
     *
     * <p>Ranking by coverage was exactly backwards for the job. Sampling films put
     * "English-language films" and "American films" at the top and left "Films set in
     * Sierra Leone" at the bottom — the one category that actually names a field value.
     * A category every sampled article carries describes the SAMPLE, not the member, so
     * it cannot be naming something that varies per member; the fewer articles share a
     * category, the more it is saying about those articles specifically.
     *
     * <p>This is a ranking, not a filter: nothing is hidden, and a common category is
     * still one scroll away for a field that genuinely wants one. With a single seed
     * there is nothing to discriminate — every category is shared by everything read —
     * and the order falls back to alphabetical, which is the honest answer rather than a
     * confident-looking one.
     */
    static void rankByDistinctiveness(List<List<Object>> rows) {
        if (rows == null) return;
        rows.sort((left, right) -> {
            int shared = Integer.compare(shareCount(left), shareCount(right));
            return shared != 0 ? shared : String.valueOf(left.get(0))
                    .compareToIgnoreCase(String.valueOf(right.get(0)));
        });
    }

    private static int shareCount(List<Object> row) {
        return row.size() > 1 && row.get(1) instanceof Number count ? count.intValue() : 0;
    }

    private List<String> sample(QueryContext context) throws Exception {
        if (!WikidataIds.isQid(typeQid)) return List.of();
        // Its own step: this request decides which articles the rest of the query
        // reads, and it runs before any of them, so a reader waiting on the slow
        // part should see what picked the subjects — and be able to open it.
        return context.step(
                "Sample instances to read",
                "SPARQL",
                "wd:<type> <- P31 - ?item  (sampled)",
                java.util.Map.of("typeQid", typeQid,
                        "sampleSize", String.valueOf(sampleSize)),
                step -> {
                    List<String> result = new ArrayList<>();
                    String sparql = SparqlQueries.sampleInstancesByP31(typeQid, sampleSize);
                    step.request(sparql);
                    for (WikidataBinding binding : WikidataAccess
                            .sparql(context, Datasource.WIKIDATA).query(sparql)) {
                        String qid = binding.qid("item");
                        if (WikidataIds.isQid(qid)) result.add(qid);
                    }
                    List<String> sampled =
                            result.stream().distinct().limit(sampleSize).toList();
                    step.summary(sampled.size() + " instance(s)");
                    return sampled;
                });
    }

    @Override public int rowCount(TableQueryResult result) {
        return result == null ? 0 : result.rows().size();
    }

    private static final class Seen {
        private final LinkedHashSet<String> articles = new LinkedHashSet<>();
    }
}
