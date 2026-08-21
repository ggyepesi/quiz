package quiz.enrichment;

import datasource.SourceRef;
import datasource.enrichment.EnrichmentProposal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrichmentDecisionTest {
    @Test
    void defaultDecisionRetainsEveryIdentityActuallyUsedByItsCandidates() {
        EnrichmentProposal.IdentityCandidate first = identity("first", "Archive", "1");
        EnrichmentProposal.IdentityCandidate second = identity("second", "Museum", "2");
        EnrichmentProposal.FieldCandidate name = field("name", "first", first.source());
        EnrichmentProposal.FieldCandidate date = field("date", "second", second.source());
        EnrichmentProposal proposal = new EnrichmentProposal(
                new EnrichmentProposal.Subject("Person", "local-1", "", "Someone"),
                List.of(first, second), List.of(name, date), List.of());

        EnrichmentDecision decision = EnrichmentDecision.acceptDefault(proposal);

        assertEquals(List.of("first", "second"), decision.identities().stream()
                .map(EnrichmentProposal.IdentityCandidate::candidateId).toList());
    }

    @Test
    void literalCandidateWithoutIdentityStillProducesAReviewableDecision() {
        SourceRef source = new SourceRef("Archive", "1", "https://example.test/1");
        EnrichmentProposal proposal = new EnrichmentProposal(
                new EnrichmentProposal.Subject("Person", "local-1", "", "Someone"),
                List.of(), List.of(field("birthName", "", source)), List.of());

        EnrichmentDecision decision = EnrichmentDecision.acceptDefault(proposal);

        assertEquals(1, decision.fields().size());
        assertEquals(List.of(), decision.identities());
    }

    @Test
    void defaultDecisionRefusesTwoRecordsFromTheSameSource() {
        EnrichmentProposal.IdentityCandidate first = identity("first", "Archive", "1");
        EnrichmentProposal.IdentityCandidate second = identity("second", "archive", "2");
        EnrichmentProposal proposal = new EnrichmentProposal(
                new EnrichmentProposal.Subject("Person", "local-1", "", "Someone"),
                List.of(first, second),
                List.of(field("name", "first", first.source()),
                        field("date", "second", second.source())), List.of());

        assertTrue(EnrichmentDecision.requiresIdentityChoice(proposal));
        assertNull(EnrichmentDecision.acceptDefault(proposal));
    }

    private static EnrichmentProposal.IdentityCandidate identity(
            String id, String kind, String sourceId) {
        return new EnrichmentProposal.IdentityCandidate(id, id, List.of(), "",
                new SourceRef(kind, sourceId, "https://example.test/" + sourceId),
                1, List.of());
    }

    private static EnrichmentProposal.FieldCandidate field(
            String field, String identity, SourceRef source) {
        return new EnrichmentProposal.FieldCandidate(field, identity, field, null, "value",
                source, EnrichmentProposal.ReviewAction.FILL_IF_EMPTY);
    }
}
