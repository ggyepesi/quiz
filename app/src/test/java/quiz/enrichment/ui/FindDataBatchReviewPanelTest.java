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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void referenceProposalCaptionNamesTheCharacterAndRetainsTheReference() {
        SourceRef source = new SourceRef(
                "Wikipedia (English)", "Trojan War", "https://example.test/Trojan_War");
        DynamicViewable achilles = new DynamicViewable("Q41746", "Achilles");
        achilles.type("Character");
        EnrichmentProposal.IdentityCandidate article =
                new EnrichmentProposal.IdentityCandidate(
                        "wikipedia:Trojan War", "Trojan War", List.of(), "", source,
                        1, List.of());
        EnrichmentProposal.FieldCandidate character =
                new EnrichmentProposal.FieldCandidate(
                        "mention:Q41746", article.candidateId(), "characters", List.of(),
                        achilles, source, EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION,
                        null, true, List.of());
        EnrichmentProposal proposal = new EnrichmentProposal(
                new EnrichmentProposal.Subject(
                        "Episode", "Q12546", "Q12546", "Trojan War"),
                List.of(article), List.of(character), List.of());

        DynamicViewable change = (DynamicViewable)
                FindDataBatchReviewPanel.changeCards(proposal).getFirst();

        assertEquals("characters → Achilles", change.getDisplayName());
        assertSame(achilles, change.get("Value"),
                "the caption must not collapse the navigable Character reference to text");
    }

    @Test void reviewSummaryUsesAReferencesDisplayNameInsteadOfJavaIdentity() {
        SourceRef source = new SourceRef(
                "Wikipedia (English)", "Bastet", "https://example.test/Bastet");
        DynamicViewable cat = new DynamicViewable("Q146", "cat");
        EnrichmentProposal.FieldCandidate animal =
                new EnrichmentProposal.FieldCandidate(
                        "animal:Q146", "", "associatedAnimal", List.of(), cat, source,
                        EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION,
                        null, true, List.of());
        EnrichmentProposal proposal = new EnrichmentProposal(
                new EnrichmentProposal.Subject(
                        "EgyptianDeity", "Q146007", "Q146007", "Bastet"),
                List.of(), List.of(animal), List.of());

        String summary = FindDataBatchReviewPanel.detail(proposal);

        assertTrue(summary.contains("associatedAnimal = cat"), summary);
        assertTrue(!summary.contains("DynamicViewable@"), summary);
    }

    @Test void aRejectedBatchCallbackIsInvokedOnceAndLeavesRetryEnabled() {
        var decision = new quiz.enrichment.EnrichmentDecision(
                new EnrichmentProposal.Subject("Type", "id", "", "Item"),
                List.of(), List.of(), null,
                List.of(new EnrichmentProposal.InstanceCandidate(
                        "new-id", "Type", "new-id", "New item", List.of(),
                        new SourceRef("Source", "1", "https://example.test/1"),
                        List.of())));
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var outcome = new javax.swing.JLabel();
        var selected = new javax.swing.JButton();
        var all = new javax.swing.JButton();
        selected.setEnabled(true);
        all.setEnabled(true);

        FindDataBatchReviewPanel.stage(batch -> {
            attempts.incrementAndGet();
            return false;
        }, List.of(decision, decision), outcome, selected, all);

        assertEquals(1, attempts.get(), "one batch action has one failure surface");
        assertTrue(selected.isEnabled());
        assertTrue(all.isEnabled());
        assertEquals("Nothing staged; correct the reported problem and retry.",
                outcome.getText());
    }

    @Test void eachProposedInstanceIsOneIndependentReviewDecision() {
        SourceRef source = new SourceRef(
                "Wikipedia (English)", "Achilles", "https://example.test/Achilles");
        var first = instance("episode-1", "Achilles — Introduction", source);
        var second = instance("episode-2", "Achilles — Myths", source);
        EnrichmentProposal proposal = new EnrichmentProposal(
                new EnrichmentProposal.Subject(
                        "Character", "Q41746", "Q41746", "Achilles"),
                List.of(), List.of(reference(first, source), reference(second, source)),
                List.of(), List.of(first, second));

        List<EnrichmentProposal> split =
                FindDataBatchReviewPanel.splitInstanceCandidates(proposal);

        assertEquals(2, split.size());
        assertEquals(List.of("episode-1", "episode-2"), split.stream()
                .map(part -> part.instances().getFirst().id()).toList());
        assertEquals(List.of("episode-1", "episode-2"), split.stream()
                .map(part -> ((Viewable) part.fields().getFirst().proposedValue())
                        .getIdentifier()).toList());
    }

    private static EnrichmentProposal.InstanceCandidate instance(
            String id, String label, SourceRef source) {
        return new EnrichmentProposal.InstanceCandidate(id, "Episode", id, label,
                List.of(EnrichmentProposal.InstanceField.literal(
                        "summary", label + " text")), source, List.of());
    }

    private static EnrichmentProposal.FieldCandidate reference(
            EnrichmentProposal.InstanceCandidate instance, SourceRef source) {
        DynamicViewable value = new DynamicViewable(instance.id(), instance.displayName());
        value.type(instance.type());
        return new EnrichmentProposal.FieldCandidate(instance.id(), "", "episodes",
                List.of(), value, source,
                EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION,
                null, true, List.of());
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
