package domain.transform.ui;

import org.junit.jupiter.api.Test;
import domain.enrichment.EnrichmentRoute;
import domain.config.FieldSourceMapping;
import domain.config.FieldSourceType;
import domain.config.RuleDirection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class FieldEnrichmentRoutesTest {

    @Test
    void keepsWikidataPrimaryAndDBpediaFallbackInOneRoute() {
        FieldSourceMapping wikidata = new FieldSourceMapping();
        wikidata.sourceType(FieldSourceType.SPARQL);
        wikidata.propertyPid("P1082");
        wikidata.direction(RuleDirection.ROOT_TO_ITEM);

        FieldSourceMapping dbpedia = new FieldSourceMapping();
        dbpedia.sourceType(FieldSourceType.DBPEDIA);
        dbpedia.propertyPid("populationTotal");

        EnrichmentRoute route = FieldEnrichmentRoutes.from(
                new ReflectionDomain(java.util.List.of()), wikidata, dbpedia, null);

        assertEquals(1, route.primary().size());
        assertEquals(1, route.fallback().size());
        assertInstanceOf(domain.enrichment.WikimediaFieldEnrichmentProvider.class,
                         route.primary().getFirst());
        assertInstanceOf(DBpediaFieldEnrichmentProvider.class,
                         route.fallback().getFirst());
    }

    @Test
    void unsupportedMappingsDoNotCreateParallelProviders() {
        FieldSourceMapping manual = new FieldSourceMapping();
        manual.sourceType(FieldSourceType.MANUAL);
        manual.propertyPid("population");

        EnrichmentRoute route = FieldEnrichmentRoutes.from(
                new ReflectionDomain(java.util.List.of()), manual, null, null);

        assertEquals(0, route.allProviders().size());
    }
}
