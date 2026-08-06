package wikidata.explore.query.core;

import wikidata.WikidataSparqlClient;

/**
 * A SPARQL datasource a query is generated for — its endpoint and dialect. Making a
 * query operation request its client through {@link QueryContext#sparql(Datasource)}
 * means a Wikidata operation cannot silently fall through to DBpedia (or vice-versa):
 * the binding is explicit, not left to whoever configured the runner.
 */
public enum Datasource {
    /** Wikidata Query Service — the {@code wd:}/{@code wikibase:} prefixes are predefined. */
    WIKIDATA(WikidataSparqlClient.WIKIDATA_ENDPOINT),
    /** DBpedia's Virtuoso endpoint (owl:sameAs joins, DBpedia ontology). */
    DBPEDIA(WikidataSparqlClient.DBPEDIA_ENDPOINT);

    private final String endpoint;

    Datasource(String endpoint) {
        this.endpoint = endpoint;
    }

    public String endpoint() {
        return endpoint;
    }
}
