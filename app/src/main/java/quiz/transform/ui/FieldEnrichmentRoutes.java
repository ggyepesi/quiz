package quiz.transform.ui;

import quiz.enrichment.EnrichmentProvider;
import quiz.enrichment.EnrichmentRoute;
import wikidata.explore.model.FieldSourceMapping;

import java.util.List;

/** Builds a routed Find Data plan from the field-source mappings already used by the UI.
 *  Shared by curate and ModelBuilder so both derive a route the same way. */
public final class FieldEnrichmentRoutes {

    private FieldEnrichmentRoutes() { }

    public static EnrichmentRoute from(
            FieldSourceMapping primary,
            FieldSourceMapping fallback) {
        return from(primary, fallback, null);
    }

    /** With a resolver, an entity-valued property fills a reference field: the claim's
     *  QID becomes the instance the field points at. Without one the route behaves
     *  exactly as before, so callers that curate only values are unaffected. */
    public static EnrichmentRoute from(
            FieldSourceMapping primary,
            FieldSourceMapping fallback,
            quiz.enrichment.ReferenceResolver references) {
        EnrichmentProvider primaryProvider = provider(primary, references);
        EnrichmentProvider fallbackProvider = provider(fallback, references);
        return EnrichmentRoute.of(
                primaryProvider == null ? List.of() : List.of(primaryProvider),
                fallbackProvider == null ? List.of() : List.of(fallbackProvider));
    }

    private static EnrichmentProvider provider(
            FieldSourceMapping source, quiz.enrichment.ReferenceResolver references) {
        if (source == null || source.propertyPid().isBlank()) {
            return null;
        }
        return switch (source.sourceType()) {
            case SPARQL ->
                    new quiz.enrichment.WikimediaFieldEnrichmentProvider(source, references);
            case DBPEDIA -> new DBpediaFieldEnrichmentProvider(source);
            default -> null;
        };
    }
}
