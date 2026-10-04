package wikidata;

import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A saved 625-member population produced a valid {@code VALUES ?value} query but
 * failed before execution with HTTP 414: every SPARQL request was encoded into a
 * GET URL. Request representation is a transport decision, so every query caller
 * gets the same GET/POST choice here.
 */
class WikidataSparqlRequestMethodTest {

    @Test void theReported625QidPopulationTravelsInTheRequestBody() {
        String qids = IntStream.range(0, 625)
                .mapToObj(i -> "wd:Q" + (100_000_000 + i))
                .collect(Collectors.joining(" "));
        String sparql = "SELECT ?value WHERE { VALUES ?value { " + qids + " } }";

        HttpRequest request = WikidataSparqlClient.request(
                WikidataSparqlClient.WIKIDATA_ENDPOINT, "test", sparql);

        assertEquals("POST", request.method());
        assertEquals(WikidataSparqlClient.WIKIDATA_ENDPOINT,
                request.uri().toString(), "the query must not remain in the URL");
        assertEquals("application/x-www-form-urlencoded; charset=UTF-8",
                request.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(WikidataSparqlClient.formBody(sparql).startsWith("query=SELECT+"));
        assertTrue(WikidataSparqlClient.formBody(sparql).endsWith("&format=json"));
    }

    @Test void aShortQueryRemainsAGet() {
        String sparql = "SELECT ?value WHERE { wd:Q1 wdt:P31 ?value }";

        HttpRequest request = WikidataSparqlClient.request(
                WikidataSparqlClient.WIKIDATA_ENDPOINT, "test", sparql);

        assertEquals("GET", request.method());
        assertTrue(request.uri().getRawQuery().startsWith("query=SELECT+"));
        assertFalse(request.headers().firstValue("Content-Type").isPresent());
    }
}
