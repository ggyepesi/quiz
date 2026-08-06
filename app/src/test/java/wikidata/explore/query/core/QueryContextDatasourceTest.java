package wikidata.explore.query.core;

import org.junit.jupiter.api.Test;
import wikidata.WikidataSparqlClient;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryContextDatasourceTest {

    @Test
    void datasourceSelectionIsExplicitAndMissingBindingsFailImmediately() {
        try (WikidataSparqlClient wikidata = new WikidataSparqlClient("test", 1);
             WikidataSparqlClient dbpedia = new WikidataSparqlClient(
                     "test", 1, Datasource.DBPEDIA.endpoint())) {
            QueryContext context = new QueryContext(wikidata, null)
                    .withDatasource(Datasource.DBPEDIA, dbpedia);

            assertSame(wikidata, context.sparql(Datasource.WIKIDATA));
            assertSame(dbpedia, context.sparql(Datasource.DBPEDIA));
        }

        QueryContext empty = new QueryContext(null, null);
        assertThrows(IllegalStateException.class,
                () -> empty.sparql(Datasource.WIKIDATA));
    }

    @Test
    void factoryOwnsItsSecondaryClientsAndCannotMintContextsAfterClose() {
        try (WikidataSparqlClient wikidata = new WikidataSparqlClient("test", 1)) {
            QueryFactory factory = new QueryFactory(wikidata, null, "test");
            QueryContext context = factory.newContext();
            assertSame(wikidata, context.sparql(Datasource.WIKIDATA));
            factory.close();
            factory.close(); // ownership cleanup is deliberately idempotent
            assertThrows(IllegalStateException.class, factory::newContext);
        }
    }
}
