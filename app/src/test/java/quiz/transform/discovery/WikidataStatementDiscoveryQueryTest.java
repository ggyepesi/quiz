package quiz.transform.discovery;

import org.junit.jupiter.api.Test;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.query.core.WikidataAccess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The selected sample is one batched job, not one remote request per QID. */
class WikidataStatementDiscoveryQueryTest {

    @Test void fiftyFiveInstancesStartAsSixMultiQidRequests() throws Exception {
        List<String> issued = Collections.synchronizedList(new ArrayList<>());
        try (WikidataSparqlClient client = new WikidataSparqlClient("test") {
            @Override public List<WikidataBinding> query(String sparql) {
                issued.add(sparql);
                return List.of();
            }
        }) {
            List<String> qids = java.util.stream.IntStream.rangeClosed(1, 55)
                    .mapToObj(i -> "Q" + i).toList();

            var result = new WikidataStatementDiscoveryQuery(qids).execute(
                    WikidataAccess.of(client, null).bind());

            assertEquals(6, issued.size());
            assertEquals(55, result.size(),
                    "an entity with no returned statements is still a completed result");
            assertTrue(issued.getFirst().contains(
                    "VALUES ?e { wd:Q1 wd:Q2 wd:Q3 wd:Q4 wd:Q5 wd:Q6 wd:Q7 wd:Q8 wd:Q9 wd:Q10 }"));
            assertTrue(issued.getLast().contains("VALUES ?e { wd:Q51 wd:Q52 wd:Q53 wd:Q54 wd:Q55 }"));
        }
    }
}
