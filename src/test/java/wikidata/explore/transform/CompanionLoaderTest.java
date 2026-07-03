package wikidata.explore.transform;

import org.junit.jupiter.api.Test;

import java.util.List;

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
}
