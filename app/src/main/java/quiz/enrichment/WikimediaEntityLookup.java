package quiz.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transport-neutral Wikidata entity lookup.
 *
 * <p>This layer knows how to fetch and parse labels, aliases, sitelinks and typed,
 * ranked claims (including qualifiers). It deliberately does not interpret a
 * particular property: converting CommonsMedia into an image URL, quantities into
 * units, or time values into dates belongs to a discovery provider.
 */
public final class WikimediaEntityLookup {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final JsonFetcher fetcher;

    public WikimediaEntityLookup() {
        this(defaultFetcher());
    }

    WikimediaEntityLookup(JsonFetcher fetcher) {
        this.fetcher = fetcher;
    }

    public Query<EntityRecord> byQid(String qid) {
        if (qid == null || !qid.matches("Q\\d+")) {
            throw new IllegalArgumentException("A Wikidata QID is required");
        }
        URI uri = api(qid);
        return new Query<>() {
            @Override public String purpose() {
                return "Read Wikidata entity";
            }

            @Override public String skeleton() {
                return "wbgetentities labels, aliases, sitelinks and claims";
            }

            @Override public String queryType() {
                return "Wikidata API";
            }

            @Override public String description() {
                return "Wikidata entity lookup";
            }

            @Override public Map<String, String> parameters() {
                return Map.of("qid", qid);
            }

            @Override public EntityRecord execute(QueryContext context)
                    throws Exception {
                return context.step(
                        "Read Wikidata entity",
                        "Wikidata API",
                        skeleton(),
                        parameters(),
                        step -> {
                            step.request(uri.toString());
                            EntityRecord result =
                                    parse(qid, MAPPER.readTree(fetcher.fetch(uri)));
                            step.summary(result.claims().size()
                                    + " claimed propert"
                                    + (result.claims().size() == 1 ? "y" : "ies"));
                            return result;
                        });
            }

            @Override public int rowCount(EntityRecord result) {
                return result == null ? 0 : result.claims().size();
            }
        };
    }

    static EntityRecord parse(String qid, JsonNode root) {
        JsonNode entity = root == null
                ? MAPPER.createObjectNode()
                : root.path("entities").path(qid);
        Map<String, List<Claim>> claims = new LinkedHashMap<>();
        JsonNode claimProperties = entity.path("claims");
        if (claimProperties.isObject()) {
            claimProperties.fields().forEachRemaining(entry -> {
                List<Claim> values = new ArrayList<>();
                if (entry.getValue().isArray()) {
                    for (JsonNode statement : entry.getValue()) {
                        values.add(parseClaim(entry.getKey(), statement));
                    }
                }
                claims.put(entry.getKey(), List.copyOf(values));
            });
        }

        Map<String, String> sitelinks = new LinkedHashMap<>();
        JsonNode sites = entity.path("sitelinks");
        if (sites.isObject()) {
            sites.fields().forEachRemaining(entry -> {
                String title = entry.getValue().path("title").asText("");
                if (!title.isBlank()) {
                    sitelinks.put(entry.getKey(), title);
                }
            });
        }

        List<String> aliases = new ArrayList<>();
        JsonNode aliasNodes = entity.path("aliases").path("en");
        if (aliasNodes.isArray()) {
            for (JsonNode alias : aliasNodes) {
                String value = alias.path("value").asText("");
                if (!value.isBlank()) aliases.add(value);
            }
        }

        return new EntityRecord(
                qid,
                entity.path("labels").path("en").path("value").asText(""),
                entity.path("descriptions").path("en").path("value").asText(""),
                aliases,
                sitelinks,
                claims);
    }

    private static Claim parseClaim(String property, JsonNode statement) {
        JsonNode mainsnak = statement.path("mainsnak");
        return new Claim(
                property,
                statement.path("rank").asText("normal"),
                typedValue(mainsnak),
                parseQualifiers(statement.path("qualifiers")));
    }

    private static Map<String, List<TypedValue>> parseQualifiers(JsonNode qualifiers) {
        Map<String, List<TypedValue>> result = new LinkedHashMap<>();
        if (!qualifiers.isObject()) {
            return result;
        }
        qualifiers.fields().forEachRemaining(entry -> {
            List<TypedValue> values = new ArrayList<>();
            if (entry.getValue().isArray()) {
                for (JsonNode snak : entry.getValue()) {
                    values.add(typedValue(snak));
                }
            }
            result.put(entry.getKey(), List.copyOf(values));
        });
        return result;
    }

    private static TypedValue typedValue(JsonNode snak) {
        String datatype = snak.path("datatype").asText("");
        JsonNode value = snak.path("datavalue").path("value");
        Object raw = value.isMissingNode() || value.isNull()
                ? null : MAPPER.convertValue(value, Object.class);
        return new TypedValue(datatype, raw);
    }

    static JsonFetcher defaultFetcher() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        return uri -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "QuizProject/1.0")
                    .header("Accept", "application/json")
                    .GET().build();
            return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        };
    }

    private static URI api(String qid) {
        return URI.create("https://www.wikidata.org/w/api.php"
                + "?action=wbgetentities&ids=" + qid
                + "&props=claims%7Csitelinks%7Clabels%7Cdescriptions%7Caliases"
                + "&languages=en&format=json");
    }

    public record EntityRecord(
            String qid,
            String label,
            String description,
            List<String> aliases,
            Map<String, String> sitelinks,
            Map<String, List<Claim>> claims) {

        public EntityRecord {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            sitelinks = sitelinks == null ? Map.of() : Map.copyOf(sitelinks);
            claims = claims == null ? Map.of() : Map.copyOf(claims);
        }

        public List<Claim> claims(String property) {
            return claims.getOrDefault(property, List.of());
        }

        public String sitelink(String site) {
            return sitelinks.getOrDefault(site, "");
        }
    }

    public record Claim(
            String property,
            String rank,
            TypedValue value,
            Map<String, List<TypedValue>> qualifiers) {

        public Claim {
            qualifiers = qualifiers == null ? Map.of() : Map.copyOf(qualifiers);
        }

        public boolean deprecated() {
            return "deprecated".equals(rank);
        }
    }

    public record TypedValue(String datatype, Object value) {
        public String stringValue() {
            return value instanceof String text ? text : "";
        }
    }

    @FunctionalInterface
    interface JsonFetcher {
        String fetch(URI uri) throws Exception;
    }
}
