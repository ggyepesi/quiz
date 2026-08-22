package wikidata.explore.demo;

import wikidata.WikidataIds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Demonstrates a hybrid incoming-relation discovery strategy.
 *
 * Motivation:
 * Generic SPARQL incoming-property discovery
 *
 *     ?subject ?propUri wd:Qxxxx
 *
 * can be very expensive and often times out on Wikidata Query Service.
 *
 * This demo explores an alternative two-step approach:
 *
 *   1. Use the MediaWiki backlinks API
 *
 *        action=query
 *        list=backlinks
 *        bltitle=Qxxxx
 *
 *      to quickly obtain candidate entities that reference the target QID.
 *
 *   2. Use a focused SPARQL query restricted to those candidates:
 *
 *        VALUES ?subject { ... }
 *        ?subject ?propUri wd:Qxxxx .
 *
 *      and group by property.
 *
 * Benefits:
 *
 *   - avoids a global incoming-property scan
 *   - significantly reduces the SPARQL search space
 *   - may provide a practical alternative for incoming discovery
 *     in the model builder
 *   - useful for evaluating whether backlinks + SPARQL profiling
 *     can replace expensive generic incoming discovery
 *
 * This class is intentionally standalone so the approach can be
 * benchmarked, experimented with and compared against pure-SPARQL
 * discovery before integration into the UI.
 *
 * This suggests a broader architectural idea for the Explorer:
 * Discovery
 *     ↓
 * Use the cheapest source available
 *
 * Properties:
 *     local property cache
 *
 * Incoming candidates:
 *     MediaWiki backlinks
 *
 * Outgoing properties:
 *     SPARQL
 *
 * Labels:
 *     Wikidata labels
 *
 * Images:
 *     Commons / P18
 *
 * Full extraction:
 *     SPARQL
 */
public class BacklinksIncomingPropertyDemo {

    private static final String USER_AGENT =
            "QuizProject/1.0 (ggyepesi@gmail.com)";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        String targetQid = args.length > 0 ? args[0] : "Q8928";
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : 50;

        System.out.println("Target: " + targetQid);
        System.out.println("Backlink limit: " + limit);

        List<String> subjects = loadBacklinkQids(targetQid, limit);

        System.out.println();
        System.out.println("Backlink QIDs: " + subjects.size());
        subjects.forEach(q ->
                                 System.out.println(
                                         "  " + q
                                                 + " -> https://www.wikidata.org/wiki/" + q));
        if (subjects.isEmpty()) {
            return;
        }

        try (WikidataSparqlClient client =
                     new WikidataSparqlClient(USER_AGENT, 1)) {

            String sparql = incomingPropertyProfileQuery(targetQid, subjects);

            System.out.println();
            System.out.println("SPARQL profile query:");
            System.out.println(sparql);

            List<WikidataBinding> rows = client.query(sparql);

            System.out.println();
            System.out.println("Incoming property profile:");
            for (WikidataBinding b : rows) {
                System.out.printf(
                        "%-8s %-35s count=%s example=%s %s%n",
                        b.qid("prop"),
                        b.label("prop"),
                        b.value("count"),
                        b.qid("example"),
                        b.label("example"));
            }
        }
    }

    private static List<String> loadBacklinkQids(
            String targetQid,
            int limit) throws Exception {

        String title = cleanQid(targetQid);

        String url =
                "https://www.wikidata.org/w/api.php"
                        + "?action=query"
                        + "&list=backlinks"
                        + "&bltitle=" + enc(title)
                        + "&blnamespace=0"
                        + "&bllimit=" + Math.max(1, Math.min(limit, 500))
                        + "&format=json";

        // One polite HTTP path (UrlOpener: contact UA, 429/5xx retry, throttle, redirects).
        String body;
        try (java.io.InputStream in = objectview.utils.UrlOpener.open(url)) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        JsonNode backlinks =
                MAPPER.readTree(body)
                      .path("query")
                      .path("backlinks");

        List<String> out = new ArrayList<>();

        for (JsonNode n : backlinks) {
            String qid = n.path("title").asText("");
            if (WikidataIds.isQid(qid)) {
                out.add(qid);
            }
        }

        return out;
    }

    private static String incomingPropertyProfileQuery(
            String targetQid,
            List<String> subjects) {

        String subjectValues = wdValues(subjects);

        return """
                SELECT ?prop ?propLabel
                       (COUNT(DISTINCT ?example) AS ?count)
                       (SAMPLE(?example) AS ?example)
                       (SAMPLE(?exampleLabel) AS ?exampleLabel)
                WHERE {
                  VALUES ?example { %s }
                  ?example ?propUri wd:%s .
                  ?prop wikibase:directClaim ?propUri .
                  OPTIONAL {
                    ?example rdfs:label ?exampleLabel .
                    %s
                  }
                %s}
                GROUP BY ?prop ?propLabel
                ORDER BY DESC(?count)
                LIMIT 50
                """.formatted(subjectValues, cleanQid(targetQid),
                        wikidata.query.LabelService.labelFilter("exampleLabel", null),
                        wikidata.query.LabelService.service());
    }

    private static String wdValues(Collection<String> qids) {
        StringBuilder sb = new StringBuilder();

        for (String qid : qids) {
            qid = cleanQid(qid);
            if (!WikidataIds.isQid(qid)) {
                continue;
            }

            if (!sb.isEmpty()) {
                sb.append(" ");
            }

            sb.append("wd:").append(qid);
        }

        return sb.toString();
    }

    private static String cleanQid(String qid) {
        if (qid == null) {
            return "";
        }

        qid = qid.trim();

        if (qid.startsWith("wd:")) {
            qid = qid.substring(3);
        }

        return qid;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}