package domain.enrichment;

import domain.enrichment.EnrichmentProposal;

import work.Query;
import work.QueryContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs every applicable provider and merges their candidates into one review. */
public final class CompositeEnrichmentProvider implements EnrichmentProvider {

    private final List<EnrichmentProvider> providers;

    public CompositeEnrichmentProvider(List<EnrichmentProvider> providers) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    @Override public String name() {
        return "Combined enrichment";
    }

    @Override public boolean supports(EnrichmentRequest request) {
        return providers.stream().anyMatch(provider -> provider.supports(request));
    }

    @Override public Query<EnrichmentProposal> discover(EnrichmentRequest request) {
        List<EnrichmentProvider> applicable =
                providers.stream().filter(p -> p.supports(request)).toList();
        if (applicable.isEmpty()) {
            throw new IllegalArgumentException("No enrichment provider supports this subject");
        }
        return new Query<>() {
            @Override public String purpose() { return "Discover enrichment candidates"; }
            @Override public String skeleton() { return "Run applicable enrichment providers"; }
            @Override public String queryType() { return "Enrichment"; }
            @Override public String description() { return "Combined enrichment discovery"; }
            @Override public Map<String, String> parameters() {
                return Map.of("providers",
                        String.join(", ", applicable.stream().map(EnrichmentProvider::name).toList()));
            }

            @Override public EnrichmentProposal execute(QueryContext context) throws Exception {
                List<EnrichmentProposal.IdentityCandidate> identities = new ArrayList<>();
                List<EnrichmentProposal.FieldCandidate> fields = new ArrayList<>();
                List<EnrichmentProposal.MediaCandidate> media = new ArrayList<>();
                List<EnrichmentProposal.InstanceCandidate> instances = new ArrayList<>();
                Exception firstFailure = null;
                int completed = 0;
                for (EnrichmentProvider provider : applicable) {
                    try {
                        EnrichmentProposal result = provider.discover(request).execute(context);
                        identities.addAll(result.identities());
                        fields.addAll(result.fields());
                        media.addAll(result.media());
                        instances.addAll(result.instances());
                        completed++;
                    } catch (Exception ex) {
                        if (firstFailure == null) firstFailure = ex;
                        context.message(provider.name() + " failed: " + ex.getMessage());
                    }
                }
                if (completed == 0 && firstFailure != null) {
                    throw firstFailure;
                }
                return EnrichmentProposals.deduplicate(new EnrichmentProposal(
                        request.subject(), identities, fields, media, instances));
            }

            @Override public int rowCount(EnrichmentProposal result) {
                return result == null ? 0 : result.logicalChangeCount();
            }
        };
    }
}
