package quiz.enrichment;

import datasource.enrichment.EnrichmentProposal;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered data-source routing for one enrichment request.
 *
 * <p>Primary providers are all consulted because they may contribute complementary
 * candidates (the existing image-enrichment behaviour). Fallback providers are consulted
 * only while no usable candidate for the requested field has been found; the first fallback
 * that produces one ends the route. Provider failures remain isolated by
 * {@link FindDataProcess}, so a later fallback can still recover a useful result.</p>
 *
 * <p>This is deliberately a route over the existing {@link EnrichmentProvider} contract,
 * not a second resolver/result hierarchy. Every candidate still lands in the ordinary
 * {@link EnrichmentProposal} and therefore uses the same review and decision panels as
 * identity and missing-field resolution.</p>
 */
public record EnrichmentRoute(
        List<EnrichmentProvider> primary,
        List<EnrichmentProvider> fallback) {

    public EnrichmentRoute {
        primary = copy(primary);
        fallback = copy(fallback);
    }

    /** Existing unordered provider lists retain their old run-all behaviour. */
    public static EnrichmentRoute all(List<EnrichmentProvider> providers) {
        return new EnrichmentRoute(providers, List.of());
    }

    public static EnrichmentRoute of(
            List<EnrichmentProvider> primary,
            List<EnrichmentProvider> fallback) {
        return new EnrichmentRoute(primary, fallback);
    }

    /** Providers applicable to this particular member, preserving route order. */
    public EnrichmentRoute applicableTo(EnrichmentRequest request) {
        return new EnrichmentRoute(
                primary.stream().filter(provider -> provider.supports(request)).toList(),
                fallback.stream().filter(provider -> provider.supports(request)).toList());
    }

    public boolean isEmpty() {
        return primary.isEmpty() && fallback.isEmpty();
    }

    public List<EnrichmentProvider> allProviders() {
        List<EnrichmentProvider> providers = new ArrayList<>(primary.size() + fallback.size());
        providers.addAll(primary);
        providers.addAll(fallback);
        return List.copyOf(providers);
    }

    private static List<EnrichmentProvider> copy(List<EnrichmentProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            return List.of();
        }
        return providers.stream().filter(java.util.Objects::nonNull).toList();
    }
}
