package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionLoaderTest {

    @Test
    void buildQueryIsValueAnchoredWithOptionalRole() {
        String q = CompanionLoader.buildQuery(
                List.of("Q103916", "Q112243"), "P166", "P1686");

        assertTrue(q.contains("VALUES ?value { wd:Q103916 wd:Q112243 }"), q);
        assertTrue(q.contains("?subj p:P166 ?st"), q);
        assertTrue(q.contains("?st ps:P166 ?value"), q);
        assertTrue(q.contains("OPTIONAL { ?st pq:P1686 ?role . }"), q);
    }

    // A batch of more than five values "overruns" and throws; smaller ones succeed
    // with one winner per value. Mirrors a fat WDQS batch timing out.
    private static final class OverrunClient extends WikidataSparqlClient {
        private final List<String> known;

        OverrunClient(List<String> known) {
            super("test");
            this.known = known;
        }

        @Override
        public List<WikidataBinding> query(String sparql) {
            List<String> present = known.stream()
                    .filter(v -> sparql.contains("wd:" + v))
                    .toList();
            if (present.size() > 5) {
                throw new RuntimeException("SPARQL HTTP 500 (timeout)");
            }
            List<WikidataBinding> rows = new ArrayList<>();
            for (String value : present) {
                int n = Integer.parseInt(value.substring(1));
                Map<String, String> v = new HashMap<>();
                v.put("subj", "http://www.wikidata.org/entity/Q" + (n + 4000));
                v.put("value", "http://www.wikidata.org/entity/" + value);
                rows.add(new WikidataBinding(v));   // no role → keys back to subject
            }
            return rows;
        }
    }

    @Test
    void aFatBatchThatOverrunsSplitsSoEveryValueStillLoads() {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < 20; i++) {          // 20 / BATCH(8) → 8,8,4; the 8s overrun
            values.add("Q" + (1000 + i));
        }

        Set<List<String>> out = CompanionLoader.load(
                values, "P166", "P1686", new OverrunClient(values), null);

        assertEquals(20, out.size(),
                "halving recovers every value even though full-size batches threw");
    }
}
