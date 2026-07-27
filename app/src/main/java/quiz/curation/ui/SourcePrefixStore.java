package quiz.curation.ui;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * Remembers the URL prefix used for each source kind. The defaults are only
 * suggestions; saving a source replaces the suggestion for that kind.
 */
public final class SourcePrefixStore {

    private static final String KINDS = "source-kinds";
    private static final String PREFIX = "prefix.";
    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("Wikidata", "https://www.wikidata.org/wiki/");
        DEFAULTS.put("DBpedia", "https://dbpedia.org/resource/");
        DEFAULTS.put("Wikipedia (English)", "https://en.wikipedia.org/wiki/");
        DEFAULTS.put("NobelPrize.org", "https://www.nobelprize.org/laureate/");
    }

    private final Preferences preferences;

    public SourcePrefixStore() {
        this(Preferences.userNodeForPackage(SourcePrefixStore.class));
    }

    SourcePrefixStore(Preferences preferences) {
        this.preferences = preferences;
    }

    public List<String> kinds() {
        List<String> result = new ArrayList<>(DEFAULTS.keySet());
        String saved = preferences.get(KINDS, "");
        for (String kind : saved.split("\n")) {
            if (!kind.isBlank() && !result.contains(kind)) {
                result.add(kind);
            }
        }
        return result;
    }

    public String prefix(String kind) {
        if (kind == null || kind.isBlank()) {
            return "";
        }
        return preferences.get(key(kind), DEFAULTS.getOrDefault(kind, ""));
    }

    public void remember(String kind, String prefix) {
        if (kind == null || kind.isBlank() || prefix == null || prefix.isBlank()) {
            return;
        }
        List<String> kinds = kinds();
        if (!kinds.contains(kind.trim())) {
            kinds.add(kind.trim());
        }
        preferences.put(KINDS, String.join("\n", kinds));
        preferences.put(key(kind.trim()), prefix.trim());
    }

    public static String build(String prefix, String sourceId) {
        if (prefix == null || sourceId == null) {
            return "";
        }
        return prefix.trim() + sourceId.trim();
    }

    public static String inferPrefix(String recordUrl, String sourceId) {
        if (recordUrl == null || sourceId == null || sourceId.isBlank()) {
            return "";
        }
        String url = recordUrl.trim();
        String id = sourceId.trim();
        return url.endsWith(id) ? url.substring(0, url.length() - id.length()) : "";
    }

    public static String validationError(String url) {
        if (url == null || url.isBlank()) {
            return "Enter the exact page URL for this record.";
        }
        try {
            URI uri = URI.create(url.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                return "The URL must be an absolute http:// or https:// address.";
            }
            return null;
        } catch (IllegalArgumentException ex) {
            return "The record URL is not valid.";
        }
    }

    private static String key(String kind) {
        return PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(kind.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
