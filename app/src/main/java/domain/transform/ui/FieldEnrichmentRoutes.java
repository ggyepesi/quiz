package domain.transform.ui;

import domain.enrichment.EnrichmentProvider;
import domain.enrichment.EnrichmentRoute;
import domain.config.FieldSourceMapping;

import java.util.List;

/** Builds a routed Find Data plan from the field-source mappings already used by the UI.
 *  Shared by curate and ModelBuilder so both derive a route the same way. */
public final class FieldEnrichmentRoutes {

    private FieldEnrichmentRoutes() { }

    /** With a resolver, an entity-valued property fills a reference field: the claim's
     *  QID becomes the instance the field points at. Without one the route behaves
     *  exactly as before, so callers that curate only values are unaffected. */
    public static EnrichmentRoute from(
            domain.DomainModel domain,
            FieldSourceMapping primary,
            FieldSourceMapping fallback,
            domain.enrichment.ReferenceResolver references) {
        EnrichmentProvider primaryProvider = provider(domain, primary, references);
        EnrichmentProvider fallbackProvider = provider(domain, fallback, references);
        return EnrichmentRoute.of(
                primaryProvider == null ? List.of() : List.of(primaryProvider),
                fallbackProvider == null ? List.of() : List.of(fallbackProvider));
    }

    private static EnrichmentProvider provider(
            domain.DomainModel domain, FieldSourceMapping source,
            domain.enrichment.ReferenceResolver references) {
        if (source == null || source.propertyPid().isBlank()) {
            return null;
        }
        return switch (source.sourceType()) {
            case SPARQL ->
                    new domain.enrichment.WikimediaFieldEnrichmentProvider(
                            source, references, domain);
            case DBPEDIA -> new DBpediaFieldEnrichmentProvider(source, domain);
            case WIKIPEDIA_INFOBOX -> new WikipediaInfoboxEnrichmentProvider(source, domain);
            default -> null;
        };
    }
}
