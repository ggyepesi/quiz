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

/** Discovers native Wikipedia Infobox template parameters from selected/sample articles. */
public final class DiscoverWikipediaInfoboxQuery implements Query<TableQueryResult> {
    private final List<String> qids;
    private final String typeQid;
    private final int sampleSize;

    public DiscoverWikipediaInfoboxQuery(List<String> qids) {
        this.qids = qids == null ? List.of() : qids.stream()
                .filter(WikidataIds::isQid).distinct().limit(12).toList();
        typeQid = ""; sampleSize = 0;
    }
    public DiscoverWikipediaInfoboxQuery(String typeQid, int sampleSize) {
        qids = List.of(); this.typeQid = typeQid == null ? "" : typeQid.trim();
        this.sampleSize = Math.max(1, Math.min(12, sampleSize));
    }

    /** How many articles this discovery will read. The seed rule lives HERE, so a
     *  caller wording its explanation cannot describe a different sample than the one
     *  that runs — it was recomputing the filter and would have drifted from it. */
    public int seedCount() {
        return qids.isEmpty() ? sampleSize : qids.size();
    }

    public boolean singleArticle() { return seedCount() == 1; }

    @Override public String purpose() { return "Discover Wikipedia infobox parameters"; }
    @Override public String skeleton() {
        return "selected/sample entities -> Wikipedia sitelinks -> native Infobox parameters";
    }
    @Override public String queryType() { return "Wikipedia API"; }
    @Override public String description() { return "List observed template parameters without guessing"; }
    @Override public Map<String, String> parameters() {
        return Map.of("entities", Integer.toString(qids.size()), "typeQid", typeQid,
                "sampleSize", Integer.toString(sampleSize));
    }

    @Override public TableQueryResult execute(QueryContext context) throws Exception {
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
                Seen value = seen.computeIfAbsent(key, ignored -> new Seen());
                value.articles.add(infobox.document().title());
                if (value.examples.size() < 3 && !entry.getValue().isBlank()) {
                    value.examples.add(entry.getValue());
                }
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
        return new TableQueryResult(List.of("Template parameter", "Have", "Examples"), rows);
    }

    private List<String> sample(QueryContext context) throws Exception {
        if (!WikidataIds.isQid(typeQid)) return List.of();
        List<String> result = new ArrayList<>();
        for (WikidataBinding row : WikidataAccess.sparql(context, Datasource.WIKIDATA)
                .query(SparqlQueries.sampleInstancesByP31(typeQid, sampleSize))) {
            String qid = row.qid("item");
            if (WikidataIds.isQid(qid)) result.add(qid);
        }
        return result.stream().distinct().limit(sampleSize).toList();
    }

    @Override public int rowCount(TableQueryResult result) {
        return result == null ? 0 : result.rows().size();
    }
    private static final class Seen {
        private final LinkedHashSet<String> articles = new LinkedHashSet<>();
        private final List<String> examples = new ArrayList<>();
    }
}
