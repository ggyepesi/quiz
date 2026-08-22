package wikipedia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import datasource.evidence.ContentDigest;
import datasource.evidence.SourceDocument;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the first native Wikipedia Infobox template and its named parameters. */
public final class WikipediaInfoboxClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final WikipediaArticleClient.Fetcher fetcher;

    public WikipediaInfoboxClient() {
        this(uri -> {
            try (var in = objectview.utils.UrlOpener.open(uri.toURL())) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        });
    }

    public WikipediaInfoboxClient(WikipediaArticleClient.Fetcher fetcher) {
        this.fetcher = java.util.Objects.requireNonNull(fetcher);
    }

    public Query<Infobox> byTitle(String title) {
        String requested = title == null ? "" : title.trim();
        if (requested.isBlank()) throw new IllegalArgumentException("Article title is required");
        URI uri = api(requested);
        return new Query<>() {
            @Override public String purpose() { return "Read native Wikipedia infobox"; }
            @Override public String skeleton() { return "article wikitext -> first Infobox -> named parameters"; }
            @Override public String queryType() { return "Wikipedia API"; }
            @Override public String description() { return "Versioned native template values"; }
            @Override public Map<String, String> parameters() { return Map.of("title", requested); }
            @Override public Infobox execute(QueryContext context) throws Exception {
                return context.step("Read Wikipedia infobox", "Wikipedia API", skeleton(),
                        parameters(), step -> {
                            step.request(uri.toString());
                            Infobox result = parseResponse(JSON.readTree(fetcher.fetch(uri)));
                            step.summary(result == null ? "No infobox"
                                    : result.template() + ": " + result.parameters().size()
                                    + " parameter(s)");
                            return result;
                        });
            }
            @Override public int rowCount(Infobox result) {
                return result == null ? 0 : result.parameters().size();
            }
        };
    }

    static Infobox parseResponse(JsonNode root) {
        JsonNode parse = root.path("parse");
        String title = parse.path("title").asText("");
        String revision = parse.path("revid").asText("");
        JsonNode node = parse.path("wikitext");
        String text = node.isTextual() ? node.asText() : node.path("*").asText("");
        Parsed parsed = parseWikitext(text);
        if (title.isBlank() || revision.isBlank() || parsed == null) return null;
        SourceDocument document = new SourceDocument("Wikipedia (English)", title, title,
                pageUrl(title), revision, new ContentDigest("sha256", sha256(text)),
                Instant.now().toString());
        return new Infobox(document, parsed.template(), parsed.parameters());
    }

    /** Balanced parsing keeps pipes and equals signs inside links/templates in the value. */
    public static Parsed parseWikitext(String text) {
        if (text == null) return null;
        for (int start = text.indexOf("{{"); start >= 0; start = text.indexOf("{{", start + 2)) {
            int end = matchingTemplateEnd(text, start);
            if (end < 0) continue;
            List<String> parts = splitTopLevel(text.substring(start + 2, end - 2), '|');
            if (parts.isEmpty()) continue;
            String template = parts.get(0).trim().replace('_', ' ');
            if (!template.regionMatches(true, 0, "Infobox", 0, 7)) continue;
            Map<String, String> parameters = new LinkedHashMap<>();
            for (int i = 1; i < parts.size(); i++) {
                int equals = topLevelEquals(parts.get(i));
                if (equals <= 0) continue;
                String name = parts.get(i).substring(0, equals).trim();
                String value = cleanValue(parts.get(i).substring(equals + 1));
                if (!name.isBlank() && !value.isBlank()) parameters.put(name, value);
            }
            return new Parsed(template, parameters);
        }
        return null;
    }

    private static int matchingTemplateEnd(String text, int start) {
        int depth = 0;
        for (int i = start; i + 1 < text.length(); i++) {
            if (text.startsWith("{{", i)) { depth++; i++; }
            else if (text.startsWith("}}", i)) {
                depth--; i++;
                if (depth == 0) return i + 1;
            }
        }
        return -1;
    }

    private static List<String> splitTopLevel(String value, char separator) {
        List<String> result = new ArrayList<>();
        int templates = 0, links = 0, from = 0;
        for (int i = 0; i < value.length(); i++) {
            if (i + 1 < value.length() && value.startsWith("{{", i)) { templates++; i++; }
            else if (i + 1 < value.length() && value.startsWith("}}", i)) { templates--; i++; }
            else if (i + 1 < value.length() && value.startsWith("[[", i)) { links++; i++; }
            else if (i + 1 < value.length() && value.startsWith("]]", i)) { links--; i++; }
            else if (value.charAt(i) == separator && templates == 0 && links == 0) {
                result.add(value.substring(from, i)); from = i + 1;
            }
        }
        result.add(value.substring(from));
        return result;
    }

    private static int topLevelEquals(String value) {
        int templates = 0, links = 0;
        for (int i = 0; i < value.length(); i++) {
            if (i + 1 < value.length() && value.startsWith("{{", i)) { templates++; i++; }
            else if (i + 1 < value.length() && value.startsWith("}}", i)) { templates--; i++; }
            else if (i + 1 < value.length() && value.startsWith("[[", i)) { links++; i++; }
            else if (i + 1 < value.length() && value.startsWith("]]", i)) { links--; i++; }
            else if (value.charAt(i) == '=' && templates == 0 && links == 0) return i;
        }
        return -1;
    }

    /** Conservative display value: preserve meaning, remove only common wiki markup. */
    static String cleanValue(String raw) {
        String value = raw == null ? "" : raw.trim();
        value = value.replaceAll("(?is)<!--.*?-->", "")
                .replaceAll("(?is)<ref\\b[^>]*>.*?</ref>", "")
                .replaceAll("(?is)<ref\\b[^>]*/>", "")
                .replaceAll("\\[\\[([^]|]+)\\|([^]]+)]]", "$2")
                .replaceAll("\\[\\[([^]]+)]]", "$1")
                .replaceAll("'{2,}", "")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ").trim();
        return value;
    }

    private static URI api(String title) {
        return URI.create("https://en.wikipedia.org/w/api.php?action=parse&prop=wikitext"
                + "&redirects=1&format=json&formatversion=2&page=" + encode(title));
    }
    private static String pageUrl(String title) {
        return "https://en.wikipedia.org/wiki/" + encode(title.replace(' ', '_'));
    }
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    public record Parsed(String template, Map<String, String> parameters) {
        public Parsed { parameters = Map.copyOf(new LinkedHashMap<>(parameters)); }
    }
    public record Infobox(SourceDocument document, String template,
                          Map<String, String> parameters) {
        public Infobox { parameters = Map.copyOf(new LinkedHashMap<>(parameters)); }
        public String value(String key) { return parameters.get(key); }
    }
}
