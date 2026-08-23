package datasource.api;

import datasource.Datasources;
import datasource.EntityRef;
import datasource.api.discovery.SourceDiscoveryOperation;
import datasource.api.discovery.SourceDiscoveryRequest;
import datasource.wikipedia.WikipediaCategoryDiscoveryOperation;
import datasource.wikipedia.WikipediaDatasourceProvider;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DatasourceRegistryTest {

    @Test
    void standardRegistryExposesWikipediaCategoryDiscoveryByCapability() {
        SourceDiscoveryOperation operation = Datasources.standard().operation(
                WikipediaDatasourceProvider.ID,
                WikipediaCategoryDiscoveryOperation.ID,
                SourceDiscoveryOperation.class).orElseThrow();

        assertEquals(BindingScope.FIELD_VALUE, operation.scope());
        assertEquals(SourceValueKind.TEXT, operation.outputSchema().kind());
        assertTrue(operation.outputSchema().collection());
    }

    @Test
    void categoryOperationTranslatesEntitySeedsWithoutChangingTheSeedRule() {
        SourceDiscoveryOperation operation = new WikipediaCategoryDiscoveryOperation();
        var query = operation.discover(new SourceDiscoveryRequest(List.of(
                EntityRef.wikidata("Q1"), EntityRef.wikidata("Q2")), Map.of()));

        assertEquals("2", query.parameters().get("entities"));
        assertEquals("Discover Wikipedia categories", query.purpose());
    }

    @Test
    void duplicateProviderIdsAreRejectedAtCompositionTime() {
        var one = new WikipediaDatasourceProvider();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new DatasourceRegistry(List.of(one, one)));
        assertTrue(failure.getMessage().contains(WikipediaDatasourceProvider.ID));
    }

    @Test void anAbsentOperationIsAFaultRatherThanAnEmptyResult() {
        // The registry that fails loudly on a duplicate id let a missing one pass
        // quietly, and a caller with nothing to run would silently do nothing — the
        // failure shape this codebase keeps paying for.
        DatasourceRegistry registry = datasource.Datasources.standard();

        IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> registry.require("wikipedia", "no-such-operation",
                        DatasourceOperation.class));

        assertTrue(failure.getMessage().contains("no-such-operation"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("wikidata"),
                "and says what IS registered, so the fault is diagnosable: "
                        + failure.getMessage());
    }

    @Test void theCompositionRootHandsOutRegistriesRatherThanSharingOne() {
        // A shared instance would be a service locator: a caller could not be given a
        // different set of providers, which is the whole point of a registry.
        assertTrue(datasource.Datasources.standard()
                        != datasource.Datasources.standard(),
                "each caller is composed for, not served from a global");
        assertEquals(2, datasource.Datasources.standard().providers().size(),
                "Wikipedia contributes evidence about entities; Wikidata is what an "
                        + "entity is here");
    }
}
