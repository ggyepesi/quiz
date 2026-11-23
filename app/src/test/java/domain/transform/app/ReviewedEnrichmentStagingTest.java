package domain.transform.app;

import domain.curation.CurationStaging;
import domain.curation.ManualCuration;
import domain.datasource.SourceRef;
import domain.enrichment.EnrichmentDecision;
import domain.enrichment.EnrichmentDecisionApplier;
import domain.enrichment.EnrichmentProposal;
import domain.runtime.WikidataDynamicObject;
import domain.transform.DynamicViewable;
import objectview.Viewable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The review button's complete transaction: stage, materialize, and update live data. */
class ReviewedEnrichmentStagingTest {

    @Test void stagingAGroupedTableResultAddsItsMaterializedMembersToTheLiveGroup(
            @TempDir Path directory) {
        WikidataDynamicObject group =
                new WikidataDynamicObject("Q9133532", "Assessors of Maat");
        group.type("DeityGroup");
        group.put("members", List.of());
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(group));
        ProductDomain product = new ProductDomain(new ProductSchema(List.of(
                new ProductClass("DeityGroup", "DeityGroup", List.of(
                        new ProductField("members", "List<DeityGroupMember>",
                                true, true, "DeityGroupMember", false))),
                new ProductClass("DeityGroupMember", "DeityGroupMember", List.of()),
                new ProductClass("AssessorOfMaat", "AssessorOfMaat",
                        "DeityGroupMember", List.of())),
                List.of("DeityGroup", "AssessorOfMaat")),
                pool, Viewable.class, null);
        ManualCuration curation = new ManualCuration(
                directory.resolve("egyptiangods.curation.json").toFile());
        CuratableDomain domain = new CuratableDomain(product, curation);
        CurationStaging staging = CurationStaging.forCuration(curation);
        SourceRef source = new SourceRef("Wikipedia (English)", "Assessors of Maat",
                "https://en.wikipedia.org/wiki/Assessors_of_Maat", "field:members");

        DynamicViewable first = member("row-1", "First assessor");
        DynamicViewable second = member("row-2", "Second assessor");
        EnrichmentDecision decision = new EnrichmentDecision(
                new EnrichmentProposal.Subject("DeityGroup", group.getIdentifier(),
                        group.getIdentifier(), group.getDisplayName()),
                List.of(), List.of(field(group, first, source), field(group, second, source)),
                null, List.of(instance(first, source), instance(second, source)));

        EnrichmentDecisionApplier.StagedPreview preview =
                EnrichmentDecisionApplier.stageAndPreview(
                        domain, staging, List.of(decision));

        assertEquals(4, preview.stagedRecords());
        assertEquals(2, preview.appliedValues());
        assertEquals(List.of(), preview.unresolvedReferences());
        assertEquals(3, domain.instances().size(),
                "the two accepted row instances must enter the live instance pool");
        List<?> members = (List<?>) group.get("members");
        assertEquals(2, members.size(),
                "Stage selected card must update the displayed group immediately");
        assertSame(pool.get(1), members.get(0),
                "the group must refer to the canonical materialized instance");
        assertSame(pool.get(2), members.get(1),
                "the group must refer to the canonical materialized instance");
    }

    private static DynamicViewable member(String id, String label) {
        DynamicViewable value = new DynamicViewable(id, label);
        value.type("AssessorOfMaat");
        return value;
    }

    private static EnrichmentDecision.FieldDecision field(
            WikidataDynamicObject group, DynamicViewable member, SourceRef source) {
        EnrichmentProposal.FieldCandidate candidate =
                new EnrichmentProposal.FieldCandidate(
                        "member:" + member.getIdentifier(), "", "members",
                        group.get("members"), member, source,
                        EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION,
                        null, true, List.of());
        return new EnrichmentDecision.FieldDecision(
                candidate, candidate.suggestedAction());
    }

    private static EnrichmentProposal.InstanceCandidate instance(
            DynamicViewable member, SourceRef source) {
        return new EnrichmentProposal.InstanceCandidate(
                "table-row-instance:" + member.getIdentifier(), member.typeName(),
                member.getIdentifier(), member.getDisplayName(), List.of(), source,
                List.of(), true);
    }
}
