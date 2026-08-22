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

    /** Where a PERSON opens this query to read or re-run it. Sometimes a different service
     *  from {@link #endpoint()} — Wikidata answers machines at one host and people at another
     *  — and sometimes the same URL wearing its HTML form, as DBpedia's does. Either way it is
     *  the datasource's to know, not the log's. */
    public String browseUrl(String query) {
        String text = query == null ? "" : query;
        return switch (this) {
            case WIKIDATA -> "https://query.wikidata.org/#"
                    + java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8)
                            .replace("+", "%20");
            case DBPEDIA -> "https://dbpedia.org/sparql?query="
                    + java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8);
        };
    }

    /** How a link to {@link #browseUrl} reads. */
    public String browseLabel() {
        return this == WIKIDATA ? "Open in query service" : "Open in DBpedia query service";
    }
}
