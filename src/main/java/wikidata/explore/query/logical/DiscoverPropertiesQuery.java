package wikidata.explore.query.logical;

import wikidata.WikidataBinding;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.query.template.sparql.SparqlQueries;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DiscoverPropertiesQuery implements Query<TableQueryResult> {

    public enum Direction {
        OUTGOING,
        INCOMING
    }

    private final Direction direction;
    private final Collection<String> qids;
    private final String classQid;
    private final int limit;

    public static DiscoverPropertiesQuery outgoing(
            Collection<String> qids,
            int limit) {

        return new DiscoverPropertiesQuery(
                Direction.OUTGOING,
                qids,
                null,
                limit);
    }

    public static DiscoverPropertiesQuery incomingToClass(
            String classQid,
            int limit) {

        return new DiscoverPropertiesQuery(
                Direction.INCOMING,
                List.of(),
                classQid,
                limit);
    }

    private DiscoverPropertiesQuery(
            Direction direction,
            Collection<String> qids,
            String classQid,
            int limit) {

        this.direction = direction == null ? Direction.OUTGOING : direction;
        this.qids = qids == null ? List.of() : List.copyOf(qids);
        this.classQid = classQid == null ? "" : classQid.trim();
        this.limit = Math.max(1, limit);
    }

    @Override
    public String purpose() {
        return switch (direction) {
            case OUTGOING -> "Discover outgoing properties";
            case INCOMING -> "Discover incoming properties";
        };
    }

    @Override
    public String skeleton() {
        return switch (direction) {
            case OUTGOING ->
                    "sample QIDs -> outgoing direct predicates -> group by property";
            case INCOMING ->
                    "subjects linking to class QID -> group by direct property";
        };
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("direction", direction.toString());
        p.put("limit", String.valueOf(limit));

        if (direction == Direction.OUTGOING) {
            p.put("sampleSize", String.valueOf(qids.size()));
            p.put("qids", String.join(" ", qids));
        } else {
            p.put("classQid", classQid);
        }

        return p;
    }

    @Override
    public TableQueryResult execute(QueryContext context)
            throws Exception {

        String sparql =
                switch (direction) {
                    case OUTGOING ->
                            SparqlQueries.discoverOutgoingProperties(
                                    qids,
                                    limit);
                    case INCOMING ->
                            SparqlQueries.discoverIncomingPropertiesToClassQid(
                                    classQid,
                                    limit);
                };

        context.logText("\nSPARQL " + purpose() + "\n");
        context.logText(sparql);

        List<List<Object>> rows = new ArrayList<>();

        for (WikidataBinding b : context.sparql().query(sparql)) {
            String prop = b.qid("prop");
            String label = b.label("prop");
            String type = b.value("type");
            String count = b.value("count");
            String example = b.qid("example");
            String exampleLabel = b.label("example");

            rows.add(List.of(
                    prop == null ? "" : prop,
                    label == null ? "" : label,
                    direction.toString().toLowerCase(),
                    count == null ? "" : count,
                    type == null ? "" : type,
                    example == null ? "" : example,
                    exampleLabel == null ? "" : exampleLabel));
        }

        return new TableQueryResult(
                List.of(
                        "Property",
                        "Label",
                        "Direction",
                        "Count",
                        "Type",
                        "Example",
                        "Example label"),
                rows);
    }

    @Override
    public int rowCount(TableQueryResult result) {
        return result == null ? 0 : result.size();
    }
}