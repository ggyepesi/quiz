package datasource.evidence;

import java.util.LinkedHashMap;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Versioned native infobox parameters read from one source page. */
public record InfoboxParameters(String template, Map<String, String> parameters,
                                SourceDocument document) {
    public InfoboxParameters {
        template = template == null ? "" : template.trim();
        parameters = Map.copyOf(new LinkedHashMap<>(parameters == null ? Map.of() : parameters));
        document = Objects.requireNonNull(document, "Infobox source document is required");
        if (template.isBlank()) throw new IllegalArgumentException("Infobox template is required");
    }

    /** The value of one parameter, or null; the template must match for it to mean anything. */
    public String value(String name) {
        return parameters.get(name);
    }

    public boolean isTemplate(String name) {
        return name != null && template.equalsIgnoreCase(name.trim());
    }

    /**
     * One construction rule for this fact on both the single-page and bulk paths. The
     * digest follows the retained parameters, not unrelated article prose, so the same
     * infobox read either way has the same content identity.
     *
     * <p>The article URL is derived here rather than passed in. It was the last part of
     * the document each caller still spelled for itself, and the one whose drift nothing
     * would catch: {@link SourceDocument#documentId()} prefers the source id over the
     * URL, so two documents disagreeing only about the link still compare as the same
     * version — the reader would simply be handed the wrong article. This method had
     * already decided the source is English Wikipedia; the name says so.
     *
     * <p>{@code revision} stays blank when the response carried none, rather than
     * becoming "unknown": a document is compared by revision AND digest, and a sentinel
     * equal to itself would report an edited page as the same version. The digest carries
     * the version on its own, which is what {@link SourceDocument#versionId()} falls back
     * to.
     */
    public static InfoboxParameters fromEnglishWikipedia(String template,
            Map<String, String> parameters, String title, String revision) {
        String cleanTemplate = template == null ? "" : template.trim();
        Map<String, String> cleanParameters = parameters == null ? Map.of() : parameters;
        String cleanTitle = title == null ? "" : title.trim();
        StringBuilder material = new StringBuilder(cleanTemplate);
        new TreeMap<>(cleanParameters).forEach((name, value) ->
                material.append('\n').append(name).append('=').append(value));
        SourceDocument document = new SourceDocument("Wikipedia (English)",
                cleanTitle, cleanTitle, articleUrl(cleanTitle), revision,
                new ContentDigest("sha256", sha256(material.toString())),
                Instant.now().toString());
        return new InfoboxParameters(cleanTemplate, cleanParameters, document);
    }

    private static String articleUrl(String title) {
        return "https://en.wikipedia.org/wiki/" + URLEncoder
                .encode(title.replace(' ', '_'), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** What this page said for a declared key, or null when it says nothing — including
     *  when the page's infobox is a different template than the key names. */
    public String valueOf(Key key) {
        return key != null && isTemplate(key.template()) ? parameters.get(key.parameter()) : null;
    }

    /**
     * How a field names one infobox parameter: {@code Infobox film.country}.
     *
     * <p>The grammar is owned HERE because three places read it — the acquisition, the
     * per-instance provider and the discovery card — and they had begun to disagree: two
     * split on the first dot and the card on the last, so a dotted parameter name made
     * the card describe a different key than the code that would read it.
     *
     * <p>The template is the part before the FIRST dot. Template names do not contain
     * dots; parameter names occasionally do, and giving the remainder to the parameter is
     * what keeps such a key readable by the thing that has to look it up.
     */
    public record Key(String template, String parameter) {
        public Key {
            template = template == null ? "" : template.trim();
            parameter = parameter == null ? "" : parameter.trim();
            if (template.isBlank()) throw new IllegalArgumentException("Template is required");
            if (parameter.isBlank()) throw new IllegalArgumentException("Parameter is required");
        }

        /** Null when the text does not name a parameter, so a caller cannot half-read it. */
        public static Key parse(String key) {
            if (key == null) return null;
            int dot = key.indexOf('.');
            if (dot <= 0 || dot == key.length() - 1) return null;
            String template = key.substring(0, dot).trim();
            String parameter = key.substring(dot + 1).trim();
            return template.isBlank() || parameter.isBlank()
                    ? null : new Key(template, parameter);
        }

        @Override public String toString() { return template + "." + parameter; }
    }
}
