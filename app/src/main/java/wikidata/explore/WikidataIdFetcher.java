package wikidata.explore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class WikidataIdFetcher {

    private static final String WIKIDATA_API_TEMPLATE =
            "https://www.wikidata.org/w/api.php?action=wbgetentities&sites=enwiki&titles=%s&format=json";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Returns the Wikidata QID for the given English Wikipedia page title.
     * Returns null if not found or in case of error.
     */
    public String fetchQidForWikipediaTitle(String title) {
        try {
            String encodedTitle = title.replace(" ", "_");
            String url = String.format(WIKIDATA_API_TEMPLATE, encodedTitle);

            HttpRequest request = HttpRequest.newBuilder()
                                             .uri(URI.create(url))
                                             .header("User-Agent",
                                                     "QuizProject" +
                                                     "/1.0" +
                                                     " (ggyepesi@gmail.com)")
                                             .GET()
                                             .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("Failed to fetch QID: HTTP " + response.statusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode entities = root.get("entities");

            if (entities == null || !entities.fieldNames().hasNext()) {
                System.err.println("No entities field in response.");
                return null;
            }

            String firstKey = entities.fieldNames().next();
            if ("-1".equals(firstKey)) {  // no such entity
                return null;
            }

            return firstKey;

        } catch (Exception e) {
            System.err.println("Exception fetching QID: " + e);
            return null;
        }
    }

    // Example usage
    public static void main(String[] args) {
        WikidataIdFetcher fetcher = new WikidataIdFetcher();
    String name = "Sirius Star";
        String qid = fetcher.fetchQidForWikipediaTitle(name);
        System.out.println("QID for " + name + ": " + qid);
    }
}
