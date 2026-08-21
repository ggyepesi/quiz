package quiz.enrichment.ui;

import datasource.EntityRef;
import datasource.SourceRef;
import datasource.enrichment.EnrichmentProposal;
import datasource.evidence.EvidenceFragment;
import datasource.evidence.ExtractedClaim;
import datasource.evidence.SourceDocument;
import objectview.Viewable;
import org.junit.jupiter.api.Test;
import quiz.transform.DynamicViewable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FindDataBatchReviewPanelTest {
    @Test
    void reviewCardShowsEveryValueThatDefaultApplyWillStage() {
        SourceRef source = new SourceRef("Archive", "1", "https://example.test/1");
        EnrichmentProposal.IdentityCandidate identity =
                new EnrichmentProposal.IdentityCandidate(
                        "archive-1", "Person", List.of(), "", source, 1, List.of());
        EnrichmentProposal.FieldCandidate first = field("givenName", "Jane", source, identity);
        EnrichmentProposal.FieldCandidate second = field("familyName", "Doe", source, identity);
        EnrichmentProposal.MediaCandidate low = new EnrichmentProposal.MediaCandidate(
                "low", identity.candidateId(), "image", "https://example.test/low.jpg", "",
                source, "meta", .4, "", "", false);
        EnrichmentProposal.MediaCandidate high = new EnrichmentProposal.MediaCandidate(
                "high", identity.candidateId(), "image", "https://example.test/high.jpg", "",
                source, "meta", .9, "", "", false);
        EnrichmentProposal proposal = new EnrichmentProposal(
                new EnrichmentProposal.Subject("Person", "local-1", "", "Person"),
                List.of(identity), List.of(first, second), List.of(low, high));

        List<Viewable> changes = FindDataBatchReviewPanel.changeCards(proposal);

        assertEquals(3, changes.size(), "two fields and only the selected best media");
        List<DynamicViewable> dynamic = changes.stream().map(DynamicViewable.class::cast).toList();
        assertEquals(List.of("givenName", "familyName", "image"), dynamic.stream()
                .map(v -> String.valueOf(v.get("Target field"))).toList());
        assertNotNull(dynamic.get(0).get("Evidence"));
        assertEquals("https://example.test/high.jpg", dynamic.get(2).get("Value"));
    }

    private static EnrichmentProposal.FieldCandidate field(
            String field, String value, SourceRef source,
            EnrichmentProposal.IdentityCandidate identity) {
        ExtractedClaim claim = new ExtractedClaim(
                new EntityRef("local", "local-1"), field, value, null,
                List.of(EvidenceFragment.excerpt(new SourceDocument(
                        "Archive", "1", "Record", "https://example.test/1", "7",
                        new datasource.evidence.ContentDigest("sha256", "abc"),
                        "2026-08-21T09:00:00Z"),
                        "Biography", field + " is " + value)),
                "exact", "v1", "people-a", .95, List.of());
        return new EnrichmentProposal.FieldCandidate(field, identity.candidateId(), field,
                null, value, source, EnrichmentProposal.ReviewAction.FILL_IF_EMPTY,
                null, false, List.of(claim));
    }
}
