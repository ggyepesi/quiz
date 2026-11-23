package domain.enrichment;

import domain.datasource.SourceRef;
import domain.transform.DynamicViewable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A source row is one proposed change even when accepting it creates its typed value. */
class EnrichmentProposalTest {

    @Test void aFieldAndTheTypedInstanceItAddsCountOnceEverywhere() {
        SourceRef source = new SourceRef("Wikipedia", "article", "https://example.test");
        EnrichmentProposal.InstanceCandidate first = instance("row-1", source);
        EnrichmentProposal.InstanceCandidate second = instance("row-2", source);
        EnrichmentProposal.InstanceCandidate independent = instance("note-1", source);
        EnrichmentProposal proposal = new EnrichmentProposal(
                new EnrichmentProposal.Subject("Group", "group-1", "Group"),
                List.of(), List.of(reference(first, source), reference(second, source)),
                List.of(), List.of(first, second, independent));

        assertEquals(3, proposal.logicalChangeCount(),
                "two member additions plus one independent instance, not five records");
    }

    private static EnrichmentProposal.InstanceCandidate instance(
            String id, SourceRef source) {
        return new EnrichmentProposal.InstanceCandidate(
                "candidate:" + id, "Role", id, id, List.of(), source, List.of(), true);
    }

    private static EnrichmentProposal.FieldCandidate reference(
            EnrichmentProposal.InstanceCandidate instance, SourceRef source) {
        DynamicViewable value = new DynamicViewable(instance.id(), instance.displayName());
        value.type(instance.type());
        return new EnrichmentProposal.FieldCandidate(
                "field:" + instance.id(), "", "members", List.of(), value, source,
                EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION,
                null, true, List.of());
    }
}
