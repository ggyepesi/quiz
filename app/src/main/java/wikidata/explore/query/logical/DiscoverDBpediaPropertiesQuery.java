package wikidata.explore.query.logical;

import wikidata.WikidataIds;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.query.core.Datasource;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.query.template.sparql.SparqlQueries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers which Wikipedia-infobox (DBpedia {@code dbp:}) properties are
 * available for a class: samples a few instances of the class on Wikidata, then
 * asks DBpedia (joined by {@code owl:sameAs}) which infobox properties those
 * instances carry, with how many have each + an example value. Lets the user
 * fill a DBpedia-sourced field's property by picking from a list instead of
 * having to know the {@code dbp:} name.
 */
public class DiscoverDBpediaPropertiesQuery implements Query<TableQueryResult> {

    private final String typeQid;
    private final int sampleSize;

    public DiscoverDBpediaPropertiesQuery(String typeQid, int sampleSize) {
        this.typeQid = typeQid == null ? "" : typeQid.trim();
        this.sampleSize = Math.max(1, sampleSize);
    }

    @Override
    public String purpose() {
        return "Discover DBpedia properties";
    }

    @Override
    public String skeleton() {
        return "sample class instances -> DBpedia infobox properties via owl:sameAs";
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("typeQid", typeQid);
        p.put("sampleSize", String.valueOf(sampleSize));
        return p;
    }

    @Override
    public TableQueryResult execute(QueryContext context) throws Exception {
        List<String> qids = sampleInstances(context);
        if (qids.isEmpty()) {
            context.message("Discover DBpedia: no sample instances found.\n");
            return new TableQueryResult(
                    List.of("Property", "Have", "Example"), List.of());
        }

        String dbSparql = SparqlQueries.discoverDBpediaProperties(qids);

        return context.step(
                "DBpedia infobox properties",
                "DBPEDIA",
                null,
                Map.of("instances", String.valueOf(qids.size())),
                step -> {
                    step.request(dbSparql);

                    List<List<Object>> rows = new ArrayList<>();
                    WikidataSparqlClient dbpedia = context.sparql(Datasource.DBPEDIA);
                    for (WikidataBinding b : dbpedia.query(dbSparql)) {
                        String prop = localName(b.value("p"));
                        if (prop.isEmpty()) {
                            continue;
                        }
                        rows.add(List.of(
                                prop,
                                orEmpty(b.value("n")),
                                shorten(localName(b.value("ex")))));
                    }

                    step.summary(rows.size() + " properties");
                    return new TableQueryResult(
                            List.of("Property", "Have", "Example"), rows);
                });
    }

    private List<String> sampleInstances(QueryContext context) throws Exception {
        if (!WikidataIds.isQid(typeQid)) {
            return List.of();
        }
        String sparql = SparqlQueries.sampleInstancesByP31(typeQid, sampleSize);

        return context.step(
                "Sample instances",
                "SPARQL",
                null,
                Map.of("typeQid", typeQid),
                step -> {
                    step.request(sparql);
                    List<String> qids = new ArrayList<>();
                    for (WikidataBinding b : context.sparql(Datasource.WIKIDATA).query(sparql)) {
                        String qid = b.qid("item");
                        if (qid != null && WikidataIds.isQid(qid)) {
                            qids.add(qid);
                        }
                    }
                    step.summary(qids.size() + " instances");
                    return qids;
                });
    }

    @Override
    public int rowCount(TableQueryResult result) {
        return result == null ? 0 : result.size();
    }

    @Override
    public String summary(TableQueryResult result) {
        return rowCount(result) + " properties";
    }

    // http://dbpedia.org/property/numbermainstars -> numbermainstars;
    // http://dbpedia.org/resource/Alpheratz -> Alpheratz; a literal is returned
    // as-is.
    private static String localName(String value) {
        if (value == null) {
            return "";
        }
        int i = Math.max(value.lastIndexOf('/'), value.lastIndexOf('#'));
        String tail = i >= 0 ? value.substring(i + 1) : value;
        return tail.replace('_', ' ').trim();
    }

    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
