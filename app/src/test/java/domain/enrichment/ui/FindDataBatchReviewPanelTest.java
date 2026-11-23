package domain.enrichment.ui;

import domain.datasource.EntityRef;
import domain.datasource.SourceRef;
import domain.enrichment.EnrichmentDecision;
import domain.enrichment.EnrichmentProposal;
import domain.datasource.evidence.EvidenceFragment;
import domain.datasource.evidence.ExtractedClaim;
import domain.datasource.evidence.SourceDocument;
import objectview.Viewable;
import org.junit.jupiter.api.Test;
import domain.transform.DynamicViewable;

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
        var decision = new domain.enrichment.EnrichmentDecision(
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

        assertTrue(!FindDataBatchReviewPanel.accept(batch -> {
            attempts.incrementAndGet();
            return false;
        }, List.of(decision, decision), outcome, selected, all));

        assertEquals(1, attempts.get(), "one batch action has one failure surface");
        assertTrue(selected.isEnabled());
        assertTrue(all.isEnabled());
        assertEquals("Nothing staged; correct the reported problem and retry.",
                outcome.getText());
    }

    @Test void aSuccessfulStageCompletesTheReview() {
        var decision = new EnrichmentDecision(
                new EnrichmentProposal.Subject("Type", "id", "", "Item"),
                List.of(), List.of(), null, List.of());
        var selected = new javax.swing.JButton();
        var all = new javax.swing.JButton();
        selected.setEnabled(true);
        all.setEnabled(true);

        assertTrue(FindDataBatchReviewPanel.accept(batch -> true,
                List.of(decision), new javax.swing.JLabel(), selected, all));
        assertTrue(!selected.isEnabled());
        assertTrue(!all.isEnabled());
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

    @Test void exactConfiguredTableRowsMayBeStagedTogether() {
        SourceRef source = new SourceRef(
                "Wikipedia (English)", "Assessors of Maat",
                "https://example.test/Assessors_of_Maat");
        EnrichmentProposal.InstanceCandidate row =
                new EnrichmentProposal.InstanceCandidate(
                        "row-1", "AssessorOfMaat", "row-1", "First assessor",
                        List.of(), source, List.of(), true);
        EnrichmentDecision decision = new EnrichmentDecision(
                new EnrichmentProposal.Subject(
                        "DeityGroup", "Q9133532", "Q9133532", "Assessors of Maat"),
                List.of(), List.of(), null, List.of(row));

        assertTrue(FindDataBatchReviewPanel.bulkSafeDefault(decision, false),
                "the exact configured rows are the provider-approved bulk admission");

        EnrichmentProposal.InstanceCandidate prose =
                new EnrichmentProposal.InstanceCandidate(
                        "passage-1", "Episode", "passage-1", "Interpretation",
                        List.of(), source, List.of(), false);
        EnrichmentDecision interpretive = new EnrichmentDecision(
                decision.subject(), List.of(), List.of(), null, List.of(prose));
        assertTrue(!FindDataBatchReviewPanel.bulkSafeDefault(interpretive, false),
                "interpretive instances still require individual review");
    }

    @Test void exactConfiguredTableRowsStayOneReviewResult() {
        SourceRef source = new SourceRef(
                "Wikipedia (English)", "Assessors of Maat",
                "https://example.test/Assessors_of_Maat");
        EnrichmentProposal.InstanceCandidate first =
                new EnrichmentProposal.InstanceCandidate(
                        "row-1", "AssessorOfMaat", "row-1", "First assessor",
                        List.of(), source, List.of(), true);
        EnrichmentProposal.InstanceCandidate second =
                new EnrichmentProposal.InstanceCandidate(
                        "row-2", "AssessorOfMaat", "row-2", "Second assessor",
                        List.of(), source, List.of(), true);
        domain.runtime.WikidataDynamicObject existing =
                new domain.runtime.WikidataDynamicObject("Q1", "Existing deity");
        existing.type("EgyptianDeity");
        existing.enwikiTitle("Existing deity");
        EnrichmentProposal proposal = new EnrichmentProposal(
                new EnrichmentProposal.Subject(
                        "DeityGroup", "Q9133532", "Q9133532", "Assessors of Maat"),
                List.of(), List.of(reference(first, source), reference(second, source),
                        new EnrichmentProposal.FieldCandidate(
                                "existing", "", "episodes", List.of(), existing, source,
                                EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION,
                                null, true, List.of())),
                List.of(), List.of(first, second));

        List<EnrichmentProposal> review =
                FindDataBatchReviewPanel.splitInstanceCandidates(proposal);

        assertEquals(List.of(proposal), review,
                "one configured table result must not become one top-level card per row");
        assertEquals("episodes: add 3 members (create 2 new AssessorOfMaat instances) "
                        + "[Wikipedia (English)]",
                FindDataBatchReviewPanel.detail(proposal));
        assertEquals(3, review.getFirst().fields().size(),
                "direct references to existing instances must stay in the grouped result");
        List<Viewable> changes = FindDataBatchReviewPanel.changeCards(proposal);
        assertEquals(3, changes.size(),
                "creating missing member objects is part of the collection change, not "
                        + "another visible change per member");
        assertEquals(List.of("episodes → First assessor", "episodes → Second assessor",
                        "episodes → Existing deity"),
                changes.stream().map(Viewable::getDisplayName).toList());
        DynamicViewable proposed = (DynamicViewable) changes.getFirst();
        objectview.field.LinkedTextSequence proposedCaption =
                (objectview.field.LinkedTextSequence) proposed.get("Proposal");
        assertEquals(List.of(), proposedCaption.urls(),
                "a table-only member has no invented article link");
        assertEquals(null, proposed.get("Proposal source"),
                "the common proposal page must not be repeated on every member");
        objectview.field.LinkedTextSequence proposalSource =
                FindDataBatchReviewPanel.proposalSource(proposal);
        assertEquals(List.of("https://example.test/Assessors_of_Maat"),
                proposalSource.urls());
        assertEquals("https://example.test/Assessors_of_Maat", proposalSource.text());
        DynamicViewable resolved = (DynamicViewable) changes.get(2);
        objectview.field.LinkedTextSequence memberArticle =
                (objectview.field.LinkedTextSequence) resolved.get("Member article");
        assertEquals(List.of("https://en.wikipedia.org/wiki/Existing_deity"),
                memberArticle.urls());
        assertEquals("https://en.wikipedia.org/wiki/Existing_deity",
                memberArticle.text());
        objectview.field.LinkedTextSequence resolvedCaption =
                (objectview.field.LinkedTextSequence) resolved.get("Proposal");
        assertEquals(List.of("https://en.wikipedia.org/wiki/Existing_deity"),
                resolvedCaption.urls(),
                "the visible row links only the member's own article");
        assertEquals(objectview.field.FieldRole.DISPLAY,
                proposed.dynamicFieldSchema().field("Proposal").role());
        objectview.plan.TypeShape shape = objectview.plan.TypeShape.of(
                proposed, proposed.dynamicFieldSchema(),
                objectview.field.RecordTypes::schema);
        objectview.viewconfig.ViewConfig literal = objectview.plan.ViewConfigDesugar.literal(
                objectview.viewconfig.ViewConfig.allWithMinorFields(
                        DynamicViewable.class), shape);
        objectview.plan.RenderExecutor.Level rendered = new objectview.plan.RenderExecutor(
                new objectview.plan.PlanResolver(),
                value -> value instanceof DynamicViewable dynamic
                        ? dynamic.dynamicFieldSchema() : null,
                ignored -> false, objectview.plan.Disclosure.INITIAL)
                .level(proposed, literal);
        assertEquals(List.of(),
                rendered.linkedCaption().urls(),
                "a member without an article has no repeated proposal-source link");
    }

    /** Reported: a table row whose name the Wikipedia page links showed as plain text,
     *  because a member without a loaded entity had no link at all. The page's own link
     *  on that name travels on the evidence and the member's name becomes that link. */
    @Test void aTableOnlyMemberLinksToTheArticleThePageLinksOnItsName() {
        SourceRef source = new SourceRef(
                "Wikipedia (English)", "Assessors of Maat",
                "https://example.test/Assessors_of_Maat");
        EvidenceFragment row = EvidenceFragment.excerpt(new SourceDocument(
                        "Wikipedia (English)", "Assessors of Maat", "Assessors of Maat",
                        "https://example.test/Assessors_of_Maat", "7",
                        new domain.datasource.evidence.ContentDigest("sha256", "abc"),
                        "2026-11-23T09:00:00Z"),
                "Description", "Name of the deity: Usekh-nemmet\nTask: judgement",
                List.of(new EvidenceFragment.Link("Usekh-nemmet",
                        "https://en.wikipedia.org/wiki/Usekh-nemmet", true)));
        ExtractedClaim claim = new ExtractedClaim(
                new EntityRef("wikidata", "Q9133532"), "field:members", null,
                new EntityRef("wikipedia-table-row", "row-1"), List.of(row),
                "reviewed Wikipedia table-row instance", "v1", "", .95, List.of());
        DynamicViewable member = new DynamicViewable("row-1", "Usekh-nemmet");
        member.type("AssessorOfMaat");
        EnrichmentProposal.InstanceCandidate instance =
                new EnrichmentProposal.InstanceCandidate(
                        "row-1", "AssessorOfMaat", "row-1", "Usekh-nemmet",
                        List.of(), source, List.of(), true);
        EnrichmentProposal proposal = new EnrichmentProposal(
                new EnrichmentProposal.Subject(
                        "DeityGroup", "Q9133532", "Q9133532", "Assessors of Maat"),
                List.of(), List.of(new EnrichmentProposal.FieldCandidate(
                        "row-1", "", "members", List.of(), member, source,
                        EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION,
                        null, true, List.of(claim))),
                List.of(), List.of(instance));

        DynamicViewable change =
                (DynamicViewable) FindDataBatchReviewPanel.changeCards(proposal).getFirst();
        objectview.field.LinkedTextSequence caption =
                (objectview.field.LinkedTextSequence) change.get("Proposal");
        assertEquals(List.of("https://en.wikipedia.org/wiki/Usekh-nemmet"), caption.urls());
        assertEquals("members → Usekh-nemmet", caption.text(),
                "the member's name itself is the link, as on the page");
        Object supporting = FindDataBatchReviewPanel.linkedExcerpt(row);
        assertEquals(List.of("https://en.wikipedia.org/wiki/Usekh-nemmet"),
                ((objectview.field.LinkedTextSequence) supporting).urls(),
                "the evidence text links what the page links");
        assertEquals(List.of(), EnrichmentProposal.standaloneInstances(
                        proposal.fields(), proposal.instances()),
                "creating the row instance is part of the member addition, not a second "
                        + "change: one row counts once");
    }

    @Test void aMembersArticleComesFromItsDisplayCellNotASubstringInAnotherCell() {
        SourceRef source = new SourceRef(
                "Wikipedia (English)", "Assessors of Maat",
                "https://example.test/Assessors_of_Maat");
        EvidenceFragment row = EvidenceFragment.excerpt(new SourceDocument(
                        "Wikipedia (English)", "Assessors of Maat", "Assessors of Maat",
                        source.recordUrl(), "7",
                        new domain.datasource.evidence.ContentDigest("sha256", "abc"),
                        "2026-11-23T09:00:00Z"),
                "Description", "Name: Ra-Horakhty\nRelated: Ra",
                List.of(
                        new EvidenceFragment.Link("Ra", "https://example.test/Ra"),
                        new EvidenceFragment.Link("Ra-Horakhty",
                                "https://example.test/Ra-Horakhty", true)));
        ExtractedClaim claim = new ExtractedClaim(
                new EntityRef("wikidata", "Q9133532"), "field:members", null,
                new EntityRef("wikipedia-table-row", "row-1"), List.of(row),
                "reviewed Wikipedia table-row instance", "v1", "", .95, List.of());
        DynamicViewable member = new DynamicViewable("row-1", "Ra-Horakhty");
        member.type("AssessorOfMaat");
        EnrichmentProposal proposal = new EnrichmentProposal(
                new EnrichmentProposal.Subject(
                        "DeityGroup", "Q9133532", "Q9133532", "Assessors of Maat"),
                List.of(), List.of(new EnrichmentProposal.FieldCandidate(
                        "row-1", "", "members", List.of(), member, source,
                        EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION,
                        null, true, List.of(claim))), List.of());

        DynamicViewable change = (DynamicViewable)
                FindDataBatchReviewPanel.changeCards(proposal).getFirst();
        objectview.field.LinkedTextSequence caption =
                (objectview.field.LinkedTextSequence) change.get("Proposal");
        assertEquals(List.of("https://example.test/Ra-Horakhty"), caption.urls());
    }

    private static EnrichmentProposal.InstanceCandidate instance(
            String id, String label, SourceRef source) {
        return new EnrichmentProposal.InstanceCandidate("candidate:" + id,
                "Episode", id, label,
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
                        new domain.datasource.evidence.ContentDigest("sha256", "abc"),
                        "2026-08-21T09:00:00Z"),
                        "Biography", field + " is " + value)),
                "exact", "v1", "people-a", .95, List.of());
        return new EnrichmentProposal.FieldCandidate(field, identity.candidateId(), field,
                null, value, source, EnrichmentProposal.ReviewAction.FILL_IF_EMPTY,
                null, false, List.of(claim));
    }
}
