package quiz.transform.ui;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The first enrichment source: for a member's Wikidata QID and a field name, look up
 * candidate value(s) on DBpedia — joined by {@code owl:sameAs} → the QID, reading the
 * ontology ({@code dbo:}) or raw-infobox ({@code dbp:}) property of that name. PROPOSE
 * only: the caller shows the candidates and (next) writes an accepted one as a
 * {@code Correction} (origin "dbpedia") into the curation overlay.
 */
final class DBpediaLookup {

    private DBpediaLookup() {}

    static List<String> candidates(WikidataSparqlClient dbpedia, String qid, String property) {
        if (dbpedia == null || qid == null || !qid.matches("Q\\d+")
                || property == null || property.isBlank()) {
            return List.of();
        }
        String sparql = ("""
                PREFIX dbo: <http://dbpedia.org/ontology/>
                PREFIX dbp: <http://dbpedia.org/property/>
                PREFIX owl: <http://www.w3.org/2002/07/owl#>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                SELECT DISTINCT ?val ?valLabel WHERE {
                  ?dbr owl:sameAs <http://www.wikidata.org/entity/%1$s> .
                  { ?dbr dbo:%2$s ?val } UNION { ?dbr dbp:%2$s ?val }
                  OPTIONAL { ?val rdfs:label ?valLabel . FILTER(LANG(?valLabel) = "en") }
                } LIMIT 25
                """).formatted(qid, property);

        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            for (WikidataBinding b : dbpedia.query(sparql)) {
                String label = b.value("valLabel");
                String val = label != null && !label.isBlank()
                        ? label : readable(b.value("val"));
                if (val != null && !val.isBlank()) {
                    out.add(val.trim());
                }
            }
        } catch (Exception ignore) {
            // best-effort; empty candidates on failure
        }
        return new ArrayList<>(out);
    }

    /** A dbpedia resource URI → its readable last segment; a literal → itself. */
    private static String readable(String v) {
        if (v == null) {
            return null;
        }
        if (v.startsWith("http")) {
            int slash = v.lastIndexOf('/');
            return (slash >= 0 ? v.substring(slash + 1) : v).replace('_', ' ');
        }
        return v;
    }
}
