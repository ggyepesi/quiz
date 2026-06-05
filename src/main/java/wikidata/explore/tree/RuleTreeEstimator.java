package wikidata.explore.tree;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;

import java.util.List;

/** Lightweight estimation helpers. SPARQL is built by RuleTreeQueries. */
public class RuleTreeEstimator {
    private final WikidataSparqlClient client;

    public RuleTreeEstimator(WikidataSparqlClient client) {
        this.client = client;
    }

    public long countNodeResults(RuleNode node) throws Exception {
        for (WikidataBinding b : client.query(RuleTreeQueries.countNodeResultsQuery(node))) {
            return parseLong(b.value("count"));
        }
        return 0;
    }

    public boolean hasMultiValueField(RuleNode node, String propertyPid) throws Exception {
        for (WikidataBinding ignored : client.query(RuleTreeQueries.multiValueProbeQuery(node, propertyPid))) {
            return true;
        }
        return false;
    }

    public long countChildRows(RuleNode childNode, List<String> parentQids) throws Exception {
        for (WikidataBinding b : client.query(RuleTreeQueries.countChildRowsQuery(childNode, parentQids))) {
            return parseLong(b.value("count"));
        }
        return 0;
    }

    private static long parseLong(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Long.parseLong(s.trim()); }
        catch (Exception ignored) { return 0; }
    }
}
