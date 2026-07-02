package wikidata.explore.transform;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionLoaderTest {

    @Test
    void buildQueryFormsTheCompanionStatementPattern() {
        String q = CompanionLoader.buildQuery(
                List.of("Q321561", "Q222"), "P166", "P1346");

        assertTrue(q.contains("VALUES ?subj { wd:Q321561 wd:Q222 }"), q);
        assertTrue(q.contains("?subj p:P166 ?st"), q);
        assertTrue(q.contains("?st ps:P166 ?value"), q);
        assertTrue(q.contains("?st pq:P1346 ?role"), q);
    }
}
