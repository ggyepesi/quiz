package quiz.transform.ui;

import org.junit.jupiter.api.Test;
import quiz.enrichment.EnrichmentRoute;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.FieldSourceType;
import wikidata.explore.model.RuleDirection;

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

        EnrichmentRoute route = FieldEnrichmentRoutes.from(wikidata, dbpedia);

        assertEquals(1, route.primary().size());
        assertEquals(1, route.fallback().size());
        assertInstanceOf(quiz.enrichment.WikimediaFieldEnrichmentProvider.class,
                         route.primary().getFirst());
        assertInstanceOf(DBpediaFieldEnrichmentProvider.class,
                         route.fallback().getFirst());
    }

    @Test
    void unsupportedMappingsDoNotCreateParallelProviders() {
        FieldSourceMapping manual = new FieldSourceMapping();
        manual.sourceType(FieldSourceType.MANUAL);
        manual.propertyPid("population");

        EnrichmentRoute route = FieldEnrichmentRoutes.from(manual, null);

        assertEquals(0, route.allProviders().size());
    }
}
