package quiz.enrichment.ui;

import org.junit.jupiter.api.Test;
import process.swing.workflow.ProcessWorkflowResults;
import quiz.enrichment.ResolveIdentitiesDecision;
import quiz.enrichment.ResolveIdentitiesProcess;
import quiz.enrichment.ResolveIdentitiesReviewRequest.IdentityMatch;
import quiz.enrichment.ResolveIdentitiesReviewRequest.InstanceIdentity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bulk-safe is not the same as shown: every result stays visible, but only a confident
 * match may be staged by "Stage all safe". A regression here would silently assign
 * homonyms — the exact judgement the ambiguous group exists to preserve.
 */
class IdentityResultCardsTest {

    private static final ResolveIdentitiesProcess.Result RESULT =
            new ResolveIdentitiesProcess.Result(List.of(
                    new InstanceIdentity("State", "india", "India", "", List.of(
                            new IdentityMatch("Q668", "India", "country"))),
                    new InstanceIdentity("State", "zubrowka", "Zubrowka", "", List.of(
                            new IdentityMatch("Q1", "Poland", ""),
                            new IdentityMatch("Q2", "Slovakia", ""))),
                    new InstanceIdentity("State", "nowhere", "Nowhere", "", List.of())),
                    0, 3, 0);

    @Test void confidentAmbiguousAndNoMatchLandInTheirOwnTabs() {
        ProcessWorkflowResults<ResolveIdentitiesDecision.Resolved> results =
                IdentityResultCards.of(RESULT, "3 result(s)");

        assertEquals(List.of("Confident", "Ambiguous", "No match"),
                results.tabs().stream().map(ProcessWorkflowResults.Tab::title).toList());
        assertEquals(List.of(1, 1, 1),
                results.tabs().stream().map(tab -> tab.cards().size()).toList());
    }

    @Test void onlyTheConfidentCardIsBulkSafe() {
        ProcessWorkflowResults<ResolveIdentitiesDecision.Resolved> results =
                IdentityResultCards.of(RESULT, "3 result(s)");

        assertTrue(card(results, "Confident").includeInApplyAll());
        assertFalse(card(results, "Ambiguous").includeInApplyAll());
        assertFalse(card(results, "No match").includeInApplyAll());
    }

    /** The decision is read at Apply time, so it must reflect the preselected candidate —
     *  the exact hit for a confident card, the top-ranked one for an ambiguous card. */
    @Test void everyCardWithACandidateOffersADecision() {
        ProcessWorkflowResults<ResolveIdentitiesDecision.Resolved> results =
                IdentityResultCards.of(RESULT, "3 result(s)");

        assertEquals("Q668", card(results, "Confident").decision().get().qid());
        assertEquals("Q1", card(results, "Ambiguous").decision().get().qid());
        assertNull(card(results, "No match").decision().get());
    }

    /**
     * An exact label ranked below a better candidate is not bulk-safe: it lands in
     * Ambiguous with the top-ranked candidate pre-selected, so approving it is one click
     * — but a click. "Stage all safe" would otherwise write a Paris Métro station as
     * Franklin D. Roosevelt's identity.
     */
    @Test void aLowerRankedExactLabelIsReviewedRatherThanBulkStaged() {
        ProcessWorkflowResults<ResolveIdentitiesDecision.Resolved> results =
                IdentityResultCards.of(new ResolveIdentitiesProcess.Result(List.of(
                        new InstanceIdentity("President", "fdr", "Franklin D. Roosevelt", "",
                                List.of(new IdentityMatch("Q8007", "Franklin Delano Roosevelt",
                                                "president of the United States"),
                                        new IdentityMatch("Q1445234", "Franklin D. Roosevelt",
                                                "Paris Métro station")))), 0, 1, 0), "");

        assertEquals(0, tab(results, "Confident").cards().size());
        var card = tab(results, "Ambiguous").cards().get(0);
        assertFalse(card.includeInApplyAll());
        assertEquals("Q8007", card.decision().get().qid());
    }

    /** Staging writes the identity onto the INSTANCE, not onto the matched entity. */
    @Test void aDecisionCarriesTheInstanceItIdentifies() {
        ResolveIdentitiesDecision.Resolved resolved =
                card(IdentityResultCards.of(RESULT, ""), "Confident").decision().get();

        assertEquals("State", resolved.type());
        assertEquals("india", resolved.targetId());
        assertEquals("India", resolved.label());
    }

    private static ProcessWorkflowResults.Card<ResolveIdentitiesDecision.Resolved> card(
            ProcessWorkflowResults<ResolveIdentitiesDecision.Resolved> results, String title) {
        return tab(results, title).cards().get(0);
    }

    private static ProcessWorkflowResults.Tab<ResolveIdentitiesDecision.Resolved> tab(
            ProcessWorkflowResults<ResolveIdentitiesDecision.Resolved> results, String title) {
        return results.tabs().stream().filter(t -> t.title().equals(title))
                .findFirst().orElseThrow();
    }
}
