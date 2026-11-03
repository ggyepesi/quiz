package wikipedia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import datasource.evidence.InfoboxParameters;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import work.Query;
import work.QueryContext;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads source parameters and user-visible rows from the first Wikipedia infobox. */
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

    public Query<InfoboxParameters> byTitle(String title) {
        String requested = title == null ? "" : title.trim();
        if (requested.isBlank()) throw new IllegalArgumentException("Article title is required");
        URI uri = api(requested);
        return new Query<>() {
            @Override public String purpose() { return "Read native Wikipedia infobox"; }
            @Override public String skeleton() {
                return "article -> first Infobox -> source parameters + rendered rows";
            }
            @Override public String queryType() { return "Wikipedia API"; }
            @Override public String description() { return "Versioned native template values"; }
            @Override public Map<String, String> parameters() { return Map.of("title", requested); }
            @Override public InfoboxParameters execute(QueryContext context) throws Exception {
                return context.step("Read Wikipedia infobox", "Wikipedia API", skeleton(),
                        parameters(), step -> {
                            step.request(uri.toString());
                            InfoboxParameters result =
                                    parseResponse(JSON.readTree(fetcher.fetch(uri)));
                            step.summary(result == null ? "No infobox"
                                    : result.parameters().size() + " source parameter(s), "
                                    + result.renderedFields().size() + " rendered row(s)");
                            return result;
                        });
            }
            @Override public int rowCount(InfoboxParameters result) {
                return result == null ? 0
                        : result.parameters().size() + result.renderedFields().size();
            }
        };
    }

    public static InfoboxParameters parseResponse(JsonNode root) {
        JsonNode parse = root.path("parse");
        String title = parse.path("title").asText("");
        String revision = parse.path("revid").asText("");
        JsonNode node = parse.path("wikitext");
        String text = node.isTextual() ? node.asText() : node.path("*").asText("");
        Parsed parsed = parseWikitext(text);
        JsonNode renderedNode = parse.path("text");
        String rendered = renderedNode.isTextual()
                ? renderedNode.asText() : renderedNode.path("*").asText("");
        Map<String, String> fields = parseRenderedFields(rendered);
        if (title.isBlank() || revision.isBlank()
                || (parsed == null && fields.isEmpty())) return null;
        return InfoboxParameters.fromEnglishWikipedia(
                parsed == null ? "" : parsed.template(),
                parsed == null ? Map.of() : parsed.parameters(), fields, title, revision);
    }

    /**
     * Reads the user-visible label/value rows of the first rendered infobox.
     *
     * <p>Rows of nested tables are ignored: only a row's direct label and data cells count.
     * This matters for element infoboxes, whose miniature periodic table contains hundreds of
     * nested rows that are presentation, not fields.</p>
     */
    public static Map<String, String> parseRenderedFields(String html) {
        if (html == null || html.isBlank()) return Map.of();
        Element table = Jsoup.parse(html).selectFirst("table.infobox");
        if (table == null) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (Element row : table.select("tr")) {
            if (enclosingTable(row) != table) continue;
            Element label = directChild(row, "th", "infobox-label");
            Element data = directChild(row, "td", "infobox-data");
            if (label == null || data == null) continue;
            String name = label.text().trim();
            String value = data.text().trim();
            if (name.isBlank() || value.isBlank()) continue;
            result.merge(name, value, (left, right) -> left.equals(right)
                    || left.contains("; " + right) ? left : left + "; " + right);
        }
        return Map.copyOf(result);
    }

    private static Element enclosingTable(Element element) {
        for (Element current = element.parent(); current != null; current = current.parent()) {
            if (current.tagName().equals("table")) return current;
        }
        return null;
    }

    private static Element directChild(Element parent, String tag, String className) {
        for (Element child : parent.children()) {
            if (child.tagName().equals(tag) && child.hasClass(className)) return child;
        }
        return null;
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
        // parseResponse deliberately refuses an unversioned document. Ask for the
        // revision in the SAME request; action=parse does not include it merely because
        // wikitext was requested (the live Blood Diamond response exposed this gap).
        return URI.create("https://en.wikipedia.org/w/api.php?action=parse&prop=wikitext%7Ctext%7Crevid"
                + "&redirects=1&format=json&formatversion=2&page=" + encode(title));
    }
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
    /** An infobox read out of wikitext alone, before any document can vouch for it.
     *  It becomes an {@link InfoboxParameters} the moment a version is known — which is
     *  why it is a separate type and not a nullable-document one. */
    public record Parsed(String template, Map<String, String> parameters) {
        public Parsed { parameters = Map.copyOf(new LinkedHashMap<>(parameters)); }
    }
}
