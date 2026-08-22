package wikidata.explore.query.logical;

import quiz.enrichment.WikimediaEntityLookup;
import wikidata.WikidataBinding;
import wikidata.WikidataIds;
import wikidata.explore.query.core.Datasource;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.query.template.sparql.SparqlQueries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

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
        rows.sort((left, right) -> {
            int count = Integer.compare(((Number) right.get(1)).intValue(),
                    ((Number) left.get(1)).intValue());
            return count != 0 ? count : String.valueOf(left.get(0))
                    .compareToIgnoreCase(String.valueOf(right.get(0)));
        });
        return new TableQueryResult(List.of("Category", "Have", "Examples"), rows);
    }

    private List<String> sample(QueryContext context) throws Exception {
        if (!WikidataIds.isQid(typeQid)) return List.of();
        List<String> result = new ArrayList<>();
        String sparql = SparqlQueries.sampleInstancesByP31(typeQid, sampleSize);
        for (WikidataBinding binding : context.sparql(Datasource.WIKIDATA).query(sparql)) {
            String qid = binding.qid("item");
            if (WikidataIds.isQid(qid)) result.add(qid);
        }
        return result.stream().distinct().limit(sampleSize).toList();
    }

    @Override public int rowCount(TableQueryResult result) {
        return result == null ? 0 : result.rows().size();
    }

    private static final class Seen {
        private final LinkedHashSet<String> articles = new LinkedHashSet<>();
    }
}
