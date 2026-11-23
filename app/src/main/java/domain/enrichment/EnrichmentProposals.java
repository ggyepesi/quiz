package domain.enrichment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** One merge rule for proposals returned by one or several datasource providers. */
final class EnrichmentProposals {

    private EnrichmentProposals() { }

    static EnrichmentProposal deduplicate(EnrichmentProposal proposal) {
        Map<String, EnrichmentProposal.IdentityCandidate> identities = new LinkedHashMap<>();
        for (EnrichmentProposal.IdentityCandidate identity : proposal.identities()) {
            String key = identity.source().kind() + "\u0000"
                    + identity.source().sourceId() + "\u0000"
                    + identity.source().recordUrl();
            identities.putIfAbsent(key, identity);
        }
        Map<String, EnrichmentProposal.MediaCandidate> media = new LinkedHashMap<>();
        for (EnrichmentProposal.MediaCandidate candidate : proposal.media()) {
            media.putIfAbsent(candidate.imageUrl(), candidate);
        }
        Map<String, EnrichmentProposal.InstanceCandidate> instances = new LinkedHashMap<>();
        for (EnrichmentProposal.InstanceCandidate candidate : proposal.instances()) {
            instances.putIfAbsent(candidate.type() + '\u0000' + candidate.id(), candidate);
        }
        return new EnrichmentProposal(
                proposal.subject(), new ArrayList<>(identities.values()),
                proposal.fields(), new ArrayList<>(media.values()),
                new ArrayList<>(instances.values()));
    }
}
