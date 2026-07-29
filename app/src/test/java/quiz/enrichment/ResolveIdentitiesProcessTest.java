package quiz.enrichment;

import org.junit.jupiter.api.Test;
import process.CancellationToken;
import process.ProcessInputHandler;
import process.ProcessOutcome;
import process.ProcessRunner;
import process.ProcessStatus;
import wikidata.explore.query.core.QueryContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolveIdentitiesProcessTest {

    @Test
    void keepsAcceptedIdentityWithoutSearchingOrReviewing() {
        // Null query clients and an unsupported input handler make this a regression guard:
        // either a search or a review would fail the process.
        ProcessOutcome<ResolveIdentitiesDecision> outcome = new ProcessRunner(
                new QueryContext(null, null), null, ProcessInputHandler.unsupported())
                .run(new ResolveIdentitiesProcess(List.of(
                                new ResolveIdentitiesProcess.Subject(
                                        "State", "local-france", "France", "Q142")),
                                12),
                        new CancellationToken());

        assertEquals(ProcessStatus.SUCCEEDED, outcome.status());
        assertTrue(outcome.result().resolved().isEmpty());
        assertTrue(outcome.summary().contains("1 already resolved"));
    }

    @Test
    void reportsFailureWhenEveryUnresolvedSearchFails() {
        ProcessOutcome<ResolveIdentitiesDecision> outcome = new ProcessRunner(
                new QueryContext(null, null), null, ProcessInputHandler.unsupported())
                .run(new ResolveIdentitiesProcess(List.of(
                                new ResolveIdentitiesProcess.Subject(
                                        "State", "local-france", "France", "")),
                                12),
                        new CancellationToken());

        assertEquals(ProcessStatus.FAILED, outcome.status());
    }
}
