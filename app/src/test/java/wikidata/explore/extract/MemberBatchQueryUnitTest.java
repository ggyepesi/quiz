package wikidata.explore.extract;

import batch.WorkUnit;
import org.junit.jupiter.api.Test;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one member-batched unit: a query bounded by a batch of member QIDs, returning raw
 * rows and publishing nothing.
 *
 * <p>The loops this replaced applied rows to the shared registry AS THEY ARRIVED, so a
 * batch that failed halfway and was retried applied its early rows twice — invisible for
 * single-valued fields, because merge deduplicates.
 */
class MemberBatchQueryUnitTest {

    private static WikidataSparqlClient recordingClient(List<String> issued) {
        return new WikidataSparqlClient("test") {
            @Override public List<WikidataBinding> query(String sparql) {
                issued.add(sparql);
                return List.of(new WikidataBinding(Map.of(
                        "value", "http://www.wikidata.org/entity/Q1")));
            }
        };
    }

    private static MemberBatchQueryUnit unit(
            List<String> members, WikidataSparqlClient client) {
        return new MemberBatchQueryUnit(
                "test.unit", "Batch", members,
                qids -> "QUERY(" + String.join(",", qids) + ")",
                client, Map.of("field", "locations"));
    }

    @Test void executeIssuesTheQueryForItsOwnMembersAndReturnsRawRows() throws Exception {
        List<String> issued = new ArrayList<>();
        try (WikidataSparqlClient client = recordingClient(issued)) {
            List<WikidataBinding> rows = unit(List.of("Q1", "Q2"), client).execute();

            assertEquals(List.of("QUERY(Q1,Q2)"), issued);
            assertEquals(1, rows.size(), "rows come back untouched, nothing is published");
        }
    }

    /** The log links {@code request()} as the query, so it has to BE the query that ran.
     *  Built by the same call, not reconstructed alongside it — a second construction is
     *  a second thing to drift. */
    @Test void theRequestIsExactlyTheQueryExecuteIssues() throws Exception {
        List<String> issued = new ArrayList<>();
        try (WikidataSparqlClient client = recordingClient(issued)) {
            MemberBatchQueryUnit unit = unit(List.of("Q1", "Q2"), client);
            String request = unit.request();

            unit.execute();

            assertEquals(List.of(request), issued);
            assertNotEquals(request, unit.key(),
                            "the key identifies the batch; it is not something WDQS can run");
        }
    }

    @Test void splittingCoversTheParentExactly() throws Exception {
        List<String> issued = new ArrayList<>();
        try (WikidataSparqlClient client = recordingClient(issued)) {
            for (WorkUnit<List<WikidataBinding>> part
                    : unit(List.of("Q1", "Q2", "Q3", "Q4", "Q5"), client).split()) {
                part.execute();
            }
            // A split that lost a member would silently drop it from the run.
            assertEquals(List.of("QUERY(Q1,Q2)", "QUERY(Q3,Q4,Q5)"), issued);
        }
    }

    @Test void aSingleMemberCannotBeSplitFurther() throws Exception {
        try (WikidataSparqlClient client = recordingClient(new ArrayList<>())) {
            assertTrue(unit(List.of("Q1"), client).split().isEmpty(),
                       "the executor then has nowhere left to escalate and fails the run");
        }
    }

    @Test void batchesOfTheSameLabelGetDistinctKeysAndCarryTheirMembers() throws Exception {
        try (WikidataSparqlClient client = recordingClient(new ArrayList<>())) {
            MemberBatchQueryUnit first = unit(List.of("Q1", "Q2"), client);
            MemberBatchQueryUnit second = unit(List.of("Q3", "Q4"), client);

            // Duplicate keys fail the run outright, so a collision here would abort
            // rather than quietly drop one batch.
            assertNotEquals(first.key(), second.key());
            assertEquals("Q1,Q2",
                         first.descriptor().parameters().get(MemberBatchQueryUnit.P_MEMBERS));
            assertEquals("locations", first.descriptor().parameters().get("field"),
                         "caller parameters survive into the checkpoint descriptor");
        }
    }

    @Test void aSplitPartKeepsTheTypeLabelAndCallerParameters() throws Exception {
        try (WikidataSparqlClient client = recordingClient(new ArrayList<>())) {
            MemberBatchQueryUnit part = (MemberBatchQueryUnit)
                    unit(List.of("Q1", "Q2"), client).split().get(0);

            assertEquals("test.unit", part.descriptor().type());
            assertEquals("locations", part.descriptor().parameters().get("field"));
            assertEquals(List.of("Q1"), part.memberQids());
        }
    }
}
