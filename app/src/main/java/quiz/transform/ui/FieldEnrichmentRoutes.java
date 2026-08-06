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
        EnrichmentProvider primaryProvider = provider(primary);
        EnrichmentProvider fallbackProvider = provider(fallback);
        return EnrichmentRoute.of(
                primaryProvider == null ? List.of() : List.of(primaryProvider),
                fallbackProvider == null ? List.of() : List.of(fallbackProvider));
    }

    private static EnrichmentProvider provider(FieldSourceMapping source) {
        if (source == null || source.propertyPid().isBlank()) {
            return null;
        }
        return switch (source.sourceType()) {
            case SPARQL -> new quiz.enrichment.WikimediaFieldEnrichmentProvider(source);
            case DBPEDIA -> new DBpediaFieldEnrichmentProvider(source);
            default -> null;
        };
    }
}
