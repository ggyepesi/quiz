package wikidata.explore.query.logical;

import wikidata.WikidataBinding;
import wikidata.api.WikidataApiClient;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.query.WikidataQueryBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClassSearchQuery implements Query<TableQueryResult> {

    public enum Mode {
        API,
        SPARQL,
        BOTH
    }

    private final String text;
    private final Mode mode;
    private final int limit;

    public ClassSearchQuery(
            String text,
            Mode mode,
            int limit) {

        this.text = text == null ? "" : text.trim();
        this.mode = mode == null ? Mode.API : mode;
        this.limit = Math.max(1, limit);
    }

    @Override
    public String purpose() {
        return "Search class/type";
    }

    @Override
    public String skeleton() {
        return "search text -> Wikidata API and/or SPARQL entity search";
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("text", text);
        p.put("mode", mode.toString());
        p.put("limit", String.valueOf(limit));
        return p;
    }

    @Override
    public TableQueryResult execute(QueryContext context)
            throws Exception {

        List<Row> rows =
                switch (mode) {
                    case API -> searchApi(context);
                    case SPARQL -> searchSparql(context);
                    case BOTH -> dedupe(
                            searchApi(context),
                            searchSparql(context));
                };

        List<List<Object>> tableRows = new ArrayList<>();

        for (Row r : rows) {
            tableRows.add(List.of(
                    r.qid(),
                    r.label(),
                    r.description()));
        }

        return new TableQueryResult(
                List.of("QID", "Label", "Description"),
                tableRows);
    }

    @Override
    public int rowCount(TableQueryResult result) {
        return result == null ? 0 : result.size();
    }

    private List<Row> searchApi(QueryContext context)
            throws Exception {

        context.logText("Searching class/type using API: " + text);

        List<Row> out = new ArrayList<>();

        for (WikidataApiClient.SearchResult r :
                context.api().searchEntities(text, limit)) {

            String qid = r.qid();

            if (qid == null || qid.isBlank()) {
                continue;
            }

            out.add(new Row(
                    qid,
                    r.label() == null || r.label().isBlank()
                            ? qid
                            : r.label(),
                    r.description() == null ? "" : r.description()));
        }

        return out;
    }

    private List<Row> searchSparql(QueryContext context)
            throws Exception {

        String sparql =
                WikidataQueryBuilder.entitySearch(text, limit);

        context.logText("Searching class/type using SPARQL:");
        context.logText(sparql);

        List<Row> out = new ArrayList<>();

        for (WikidataBinding b : context.sparql().query(sparql)) {
            String qid = b.qid("item");
            String label = b.label("item");
            String desc = b.value("itemDescription");

            if (qid != null && qid.matches("Q\\d+")) {
                out.add(new Row(
                        qid,
                        label == null ? qid : label,
                        desc == null ? "" : desc));
            }
        }

        return out;
    }

    private static List<Row> dedupe(
            List<Row> a,
            List<Row> b) {

        Map<String, Row> map = new LinkedHashMap<>();

        for (Row r : a) {
            map.putIfAbsent(r.qid(), r);
        }

        for (Row r : b) {
            map.putIfAbsent(r.qid(), r);
        }

        return new ArrayList<>(map.values());
    }

    private record Row(
            String qid,
            String label,
            String description) {
    }
}