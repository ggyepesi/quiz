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

/** Discovers native Wikipedia Infobox source and rendered values from articles. */
public final class DiscoverWikipediaInfoboxQuery implements Query<TableQueryResult> {
    /** Discovery samples articles; each one may cost its own parse request. */
    public static final int MAX_ARTICLES = 12;

    private final List<String> qids;
    private final int selected;
    private final String typeQid;
    private final int sampleSize;

    public DiscoverWikipediaInfoboxQuery(List<String> qids) {
        List<String> distinct = qids == null ? List.of() : qids.stream()
                .filter(WikidataIds::isQid).distinct().toList();
        this.qids = distinct.stream().limit(MAX_ARTICLES).toList();
        selected = distinct.size();
        typeQid = ""; sampleSize = 0;
    }
    public DiscoverWikipediaInfoboxQuery(String typeQid, int sampleSize) {
        qids = List.of(); selected = 0;
        this.typeQid = typeQid == null ? "" : typeQid.trim();
        this.sampleSize = Math.max(1, Math.min(MAX_ARTICLES, sampleSize));
    }

    /** The entities this discovery reads, after the cap; empty when it samples a type. */
    public List<String> seeds() { return qids; }
    public String typeQid() { return typeQid; }
    public int sampleSize() { return sampleSize; }

    /** How many articles this discovery will read. The seed rule lives HERE, so a
     *  caller wording its explanation cannot describe a different sample than the one
     *  that runs — it was recomputing the filter and would have drifted from it. */
    public int seedCount() {
        return qids.isEmpty() ? sampleSize : qids.size();
    }

    public boolean singleArticle() { return seedCount() == 1; }

    @Override public String purpose() { return "Discover Wikipedia infobox values"; }
    @Override public String skeleton() {
        return "selected/sample entities -> Wikipedia sitelinks -> native Infobox values";
    }
    @Override public String queryType() { return "Wikipedia API"; }
    @Override public String description() {
        return "List observed source parameters and rendered rows without guessing";
    }
    @Override public Map<String, String> parameters() {
        return Map.of("entities", Integer.toString(qids.size()), "typeQid", typeQid,
                "sampleSize", Integer.toString(sampleSize));
    }

    @Override public TableQueryResult execute(QueryContext context) throws Exception {
        if (selected > qids.size()) {
            context.message("Reading the first " + qids.size() + " of " + selected
                    + " selected entities: infobox discovery samples at most "
                    + MAX_ARTICLES + " articles.\n");
        }
        List<String> subjects = qids.isEmpty() ? sample(context) : qids;
        Map<String, WikimediaEntityLookup.EntityRecord> entities =
                new WikimediaEntityLookup().byQids(subjects).execute(context);
        wikipedia.WikipediaInfoboxClient client = new wikipedia.WikipediaInfoboxClient();
        Map<String, Seen> seen = new LinkedHashMap<>();
        for (String qid : subjects) {
            var entity = entities.get(qid);
            String title = entity == null ? "" : entity.sitelink("enwiki");
            if (title == null || title.isBlank()) continue;
            var infobox = client.byTitle(title).execute(context);
            if (infobox == null) continue;
            for (var entry : infobox.parameters().entrySet()) {
                String key = infobox.template() + "." + entry.getKey();
                observe(seen, key, entry.getValue(), infobox.document().title());
            }
            for (var entry : infobox.renderedFields().entrySet()) {
                observe(seen, "Rendered." + entry.getKey(), entry.getValue(),
                        infobox.document().title());
            }
        }
        List<List<Object>> rows = new ArrayList<>();
        seen.forEach((key, value) -> rows.add(List.of(key, value.articles.size(),
                String.join("; ", value.examples))));
        rows.sort((a, b) -> {
            int coverage = Integer.compare(((Number) b.get(1)).intValue(),
                    ((Number) a.get(1)).intValue());
            return coverage != 0 ? coverage
                    : String.valueOf(a.get(0)).compareToIgnoreCase(String.valueOf(b.get(0)));
        });
        return new TableQueryResult(List.of("Infobox value", "Have", "Examples"), rows);
    }

    private static void observe(Map<String, Seen> seen, String key, String example,
            String article) {
        Seen value = seen.computeIfAbsent(key, ignored -> new Seen());
        value.articles.add(article);
        if (value.examples.size() < 3 && example != null && !example.isBlank()) {
            value.examples.add(example);
        }
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
                    for (WikidataBinding row : WikidataAccess
                            .sparql(context, Datasource.WIKIDATA).query(sparql)) {
                        String qid = row.qid("item");
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
        private final List<String> examples = new ArrayList<>();
    }
}
