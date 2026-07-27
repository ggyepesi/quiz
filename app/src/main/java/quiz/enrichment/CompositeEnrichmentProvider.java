package quiz.enrichment;

import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;

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
                Exception firstFailure = null;
                int completed = 0;
                for (EnrichmentProvider provider : applicable) {
                    try {
                        EnrichmentProposal result = provider.discover(request).execute(context);
                        identities.addAll(result.identities());
                        fields.addAll(result.fields());
                        media.addAll(result.media());
                        completed++;
                    } catch (Exception ex) {
                        if (firstFailure == null) firstFailure = ex;
                        context.message(provider.name() + " failed: " + ex.getMessage());
                    }
                }
                if (completed == 0 && firstFailure != null) {
                    throw firstFailure;
                }
                return deduplicate(new EnrichmentProposal(
                        request.subject(), identities, fields, media));
            }

            @Override public int rowCount(EnrichmentProposal result) {
                return result == null ? 0
                        : result.identities().size() + result.fields().size() + result.media().size();
            }
        };
    }

    private static EnrichmentProposal deduplicate(EnrichmentProposal proposal) {
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
        return new EnrichmentProposal(
                proposal.subject(),
                new ArrayList<>(identities.values()),
                proposal.fields(),
                new ArrayList<>(media.values()));
    }
}
