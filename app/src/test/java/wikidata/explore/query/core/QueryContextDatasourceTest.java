package wikidata.explore.query.core;

import org.junit.jupiter.api.Test;
import wikidata.WikidataSparqlClient;
import work.QueryContext;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryContextDatasourceTest {

    @Test
    void datasourceSelectionIsExplicitAndMissingBindingsFailImmediately() {
        try (WikidataSparqlClient wikidata = new WikidataSparqlClient("test", 1);
             WikidataSparqlClient dbpedia = new WikidataSparqlClient(
                     "test", 1, Datasource.DBPEDIA.endpoint())) {
            QueryContext context = WikidataAccess.of(wikidata, null)
                    .with(Datasource.DBPEDIA, dbpedia).bind();

            assertSame(wikidata, WikidataAccess.sparql(context, Datasource.WIKIDATA));
            assertSame(dbpedia, WikidataAccess.sparql(context, Datasource.DBPEDIA));
        }

        QueryContext noBinding = WikidataAccess.of(null, null).bind();
        assertThrows(IllegalStateException.class,
                () -> WikidataAccess.sparql(noBinding, Datasource.WIKIDATA));
    }

    /** A context that reaches no source at all is a legitimate context — it is what a
     *  Wikipedia read or a pure process step runs on — so asking it for Wikidata access
     *  must say that plainly rather than hand back a half-configured client. */
    @Test
    void aContextWithNoWikidataAccessSaysSoInsteadOfPretending() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> WikidataAccess.sparql(new QueryContext(), Datasource.WIKIDATA));

        assertTrue(refused.getMessage().contains("WikidataAccess"), refused.getMessage());
    }

    @Test
    void factoryOwnsItsSecondaryClientsAndCannotMintContextsAfterClose() {
        try (WikidataSparqlClient wikidata = new WikidataSparqlClient("test", 1)) {
            QueryFactory factory = new QueryFactory(wikidata, null, "test");
            QueryContext context = factory.newContext();
            assertSame(wikidata, WikidataAccess.sparql(context, Datasource.WIKIDATA));
            factory.close();
            factory.close(); // ownership cleanup is deliberately idempotent
            assertThrows(IllegalStateException.class, factory::newContext);
        }
    }
}
