package quiz.enrichment;

import wikidata.WikidataIds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import work.Query;
import work.QueryContext;

import java.net.URI;
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
        if (qid == null || !WikidataIds.isQid(qid)) {
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

    /**
     * Read many entities in ONE {@code wbgetentities} call. Discovery over a sample used to
     * issue one request per QID; the API takes 50 ids at a time, and a picker the reader is
     * waiting on should not pay a round trip per seed. Ids past the API's limit are dropped
     * — a sample is a sample. Missing entities are absent from the result rather than null.
     */
    public Query<Map<String, EntityRecord>> byQids(java.util.Collection<String> qids) {
        List<String> clean = qids == null ? List.of() : qids.stream()
                .filter(WikidataIds::isQid).distinct().limit(50).toList();
        URI uri = api(clean);
        return new Query<>() {
            @Override public String purpose() { return "Read Wikidata entities"; }
            @Override public String skeleton() {
                return "wbgetentities labels, aliases, sitelinks and claims for many ids";
            }
            @Override public String queryType() { return "Wikidata API"; }
            @Override public String description() { return "Wikidata entity lookup"; }
            @Override public Map<String, String> parameters() {
                return Map.of("ids", Integer.toString(clean.size()));
            }

            @Override public Map<String, EntityRecord> execute(QueryContext context)
                    throws Exception {
                if (clean.isEmpty()) return Map.of();
                return context.step("Read Wikidata entities", "Wikidata API", skeleton(),
                        parameters(), step -> {
                            step.request(uri.toString());
                            Map<String, EntityRecord> found = parseAll(clean,
                                    MAPPER.readTree(fetcher.fetch(uri)));
                            step.summary(found.size() + " of " + clean.size() + " entit"
                                    + (clean.size() == 1 ? "y" : "ies"));
                            return found;
                        });
            }

            @Override public int rowCount(Map<String, EntityRecord> result) {
                return result == null ? 0 : result.size();
            }
        };
    }

    static Map<String, EntityRecord> parseAll(List<String> qids, JsonNode root) {
        Map<String, EntityRecord> out = new LinkedHashMap<>();
        for (String qid : qids == null ? List.<String>of() : qids) {
            JsonNode entity = root == null ? null : root.path("entities").path(qid);
            if (entity == null || entity.isMissingNode()
                    || entity.path("missing").asBoolean(false)) continue;
            out.put(qid, parse(qid, root));
        }
        return out;
    }

    /** Resolve an English Wikipedia article title to its Wikidata entity. */
    public Query<EntityRecord> byWikipediaTitle(String title) {
        String requested = title == null ? "" : title.trim();
        if (requested.isBlank()) throw new IllegalArgumentException("A Wikipedia title is required");
        URI uri = URI.create("https://www.wikidata.org/w/api.php?action=wbgetentities"
                + "&sites=enwiki&titles=" + java.net.URLEncoder.encode(requested,
                java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")
                + "&props=labels%7Cdescriptions%7Cclaims&languages=en&format=json");
        return new Query<>() {
            @Override public String purpose() { return "Resolve Wikipedia subject"; }
            @Override public String skeleton() { return "enwiki title to Wikidata entity"; }
            @Override public String queryType() { return "Wikidata API"; }
            @Override public String description() { return "Resolve a category relation value"; }
            @Override public Map<String, String> parameters() { return Map.of("title", requested); }
            @Override public EntityRecord execute(QueryContext context) throws Exception {
                return context.step("Resolve category value", "Wikidata API", skeleton(),
                        parameters(), step -> {
                            step.request(uri.toString());
                            JsonNode entities = MAPPER.readTree(fetcher.fetch(uri)).path("entities");
                            if (!entities.isObject()) return null;
                            var fields = entities.fields();
                            while (fields.hasNext()) {
                                var entry = fields.next();
                                if (!entry.getKey().startsWith("Q")
                                        || entry.getValue().path("missing").asBoolean(false)) continue;
                                return parse(entry.getKey(), MAPPER.createObjectNode()
                                        .set("entities", entities));
                            }
                            return null;
                        });
            }
            @Override public int rowCount(EntityRecord result) { return result == null ? 0 : 1; }
        };
    }

    /** Resolve the English labels of properties/entities (P- or Q-ids) in one
     *  {@code wbgetentities} call — the API path, so it works regardless of which SPARQL
     *  endpoint the shared context points at. Ids beyond the API's 50-per-call limit are
     *  dropped (an entity rarely has that many distinct properties). */
    public Query<Map<String, String>> labels(java.util.Collection<String> ids) {
        List<String> clean = ids == null ? List.of() : ids.stream()
                .filter(WikidataIds::isId)
                .distinct().limit(50).toList();
        URI uri = labelsUri(clean);
        return new Query<>() {
            @Override public String purpose() { return "Resolve entity labels"; }
            @Override public String skeleton() { return "wbgetentities labels"; }
            @Override public String queryType() { return "Wikidata API"; }
            @Override public String description() { return "Wikidata label lookup"; }
            @Override public Map<String, String> parameters() {
                return Map.of("ids", Integer.toString(clean.size()));
            }

            @Override public Map<String, String> execute(QueryContext context)
                    throws Exception {
                if (clean.isEmpty()) {
                    return Map.of();
                }
                return context.step("Resolve labels", "Wikidata API", skeleton(),
                        parameters(), step -> {
                            step.request(uri.toString());
                            Map<String, String> out = parseLabels(
                                    MAPPER.readTree(fetcher.fetch(uri)));
                            step.summary(out.size() + " label(s)");
                            return out;
                        });
            }

            @Override public int rowCount(Map<String, String> result) {
                return result == null ? 0 : result.size();
            }
        };
    }

    static Map<String, String> parseLabels(JsonNode root) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonNode entities = root == null ? null : root.path("entities");
        if (entities != null && entities.isObject()) {
            entities.fields().forEachRemaining(entry -> {
                String label = entry.getValue().path("labels").path("en")
                        .path("value").asText("");
                if (!label.isBlank()) {
                    out.put(entry.getKey(), label);
                }
            });
        }
        return out;
    }

    private static URI labelsUri(List<String> ids) {
        return URI.create("https://www.wikidata.org/w/api.php"
                + "?action=wbgetentities&ids=" + String.join("%7C", ids)
                + "&props=labels&languages=en&format=json");
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
        // Route through UrlOpener so we inherit its Wikimedia handling: RETRY on 429
        // (Retry-After + backoff), a contact User-Agent, cross-protocol redirects and
        // self-throttling. The raw client had none, so a throttle silently emptied the
        // entity and the flag looked missing (though P41 was present). UrlOpener still
        // throws on a persistent 4xx/5xx, so a real error surfaces.
        return uri -> {
            try (java.io.InputStream in = objectview.utils.UrlOpener.open(uri.toURL())) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        };
    }

    private static URI api(String qid) {
        return api(List.of(qid));
    }

    private static URI api(List<String> qids) {
        return URI.create("https://www.wikidata.org/w/api.php"
                + "?action=wbgetentities&ids=" + String.join("%7C", qids)
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

        /**
         * The entity QIDs this entity claims for {@code property} — its P31 values, for
         * the usual caller. Spelled once here so that asking "what kind of thing is
         * this?" reads the same whether the answer came from the API record or from
         * {@code WikidataApiClient.ApiEntity.entityQids}; two spellings of one question
         * is how two callers come to disagree about the same entity.
         */
        public List<String> entityQids(String property) {
            List<String> out = new ArrayList<>();
            for (Claim claim : claims(property)) {
                if (claim.deprecated() || claim.value() == null) continue;
                if (claim.value().value() instanceof Map<?, ?> map
                        && map.get("id") instanceof String id
                        && WikidataIds.isQid(id)) {
                    out.add(id);
                }
            }
            return List.copyOf(out);
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
