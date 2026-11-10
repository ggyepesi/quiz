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

/**
 * Versioned native infobox evidence read from one source page.
 *
 * <p>{@code parameters} are what the article's source template says. Some infoboxes are
 * wrapper templates, however, and calculate user-visible facts while rendering. Those facts
 * live in {@code renderedValues}; {@code renderedFields} is their backwards-compatible text
 * projection. Keeping both beneath one evidence object lets a configured field say which
 * representation it reads without inventing a second Wikipedia source.</p>
 */
public record InfoboxParameters(String template, Map<String, String> parameters,
                                Map<String, String> renderedFields,
                                Map<String, WikipediaFragment> renderedValues,
                                SourceDocument document) {
    /** Source-only construction retained for callers whose response has no rendered HTML. */
    public InfoboxParameters(String template, Map<String, String> parameters,
                             SourceDocument document) {
        this(template, parameters, Map.of(), Map.of(), document);
    }

    /** Legacy/string construction: each rendered row becomes a text-only fragment. */
    public InfoboxParameters(String template, Map<String, String> parameters,
                             Map<String, String> renderedFields,
                             SourceDocument document) {
        this(template, parameters, renderedFields, Map.of(), document);
    }

    public InfoboxParameters {
        template = template == null ? "" : template.trim();
        parameters = Map.copyOf(new LinkedHashMap<>(parameters == null ? Map.of() : parameters));
        Map<String, WikipediaFragment> structured = new LinkedHashMap<>();
        if (renderedValues != null) renderedValues.forEach((name, value) -> {
            if (name != null && !name.isBlank() && value != null) {
                structured.put(name, value);
            }
        });
        if (renderedFields != null) renderedFields.forEach((name, value) -> {
            if (name != null && !name.isBlank()) {
                structured.putIfAbsent(name, WikipediaFragment.text(value));
            }
        });
        renderedValues = Map.copyOf(structured);
        Map<String, String> scalar = new LinkedHashMap<>();
        renderedValues.forEach((name, value) -> scalar.put(name, value.text()));
        renderedFields = Map.copyOf(scalar);
        document = Objects.requireNonNull(document, "Infobox source document is required");
        if (template.isBlank() && renderedValues.isEmpty()) {
            throw new IllegalArgumentException(
                    "An infobox template or rendered field is required");
        }
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
     * digest follows the retained structured values, not unrelated article prose, so the same
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
        return fromEnglishWikipedia(template, parameters, Map.of(), title, revision);
    }

    public static InfoboxParameters fromEnglishWikipedia(String template,
            Map<String, String> parameters, Map<String, String> renderedFields,
            String title, String revision) {
        Map<String, WikipediaFragment> structured = new LinkedHashMap<>();
        if (renderedFields != null) renderedFields.forEach((name, value) ->
                structured.put(name, WikipediaFragment.text(value)));
        return fromEnglishWikipediaValues(template, parameters, structured, title, revision);
    }

    /** Rich rendered-row construction used by the HTML parser. */
    public static InfoboxParameters fromEnglishWikipediaStructured(String template,
            Map<String, String> parameters, Map<String, WikipediaFragment> renderedValues,
            String title, String revision) {
        return fromEnglishWikipediaValues(template, parameters, renderedValues, title, revision);
    }

    private static InfoboxParameters fromEnglishWikipediaValues(String template,
            Map<String, String> parameters, Map<String, WikipediaFragment> renderedValues,
            String title, String revision) {
        String cleanTemplate = template == null ? "" : template.trim();
        Map<String, String> cleanParameters = parameters == null ? Map.of() : parameters;
        Map<String, WikipediaFragment> cleanRendered = renderedValues == null
                ? Map.of() : renderedValues;
        String cleanTitle = title == null ? "" : title.trim();
        StringBuilder material = new StringBuilder(cleanTemplate);
        new TreeMap<>(cleanParameters).forEach((name, value) ->
                material.append('\n').append(name).append('=').append(value));
        new TreeMap<>(cleanRendered).forEach((name, value) ->
                material.append("\n@rendered:").append(name).append('=').append(value.text())
                        .append("\n@links:").append(value.links().stream()
                                .map(link -> link.text() + "->" + link.targetTitle())
                                .collect(java.util.stream.Collectors.joining("|")))
                        .append("\n@media:").append(value.media().stream()
                                .map(media -> media.mediaLabel() + "->" + media.mediaUrl())
                                .collect(java.util.stream.Collectors.joining("|"))));
        SourceDocument document = new SourceDocument("Wikipedia (English)",
                cleanTitle, cleanTitle, articleUrl(cleanTitle), revision,
                new ContentDigest("sha256", sha256(material.toString())),
                Instant.now().toString());
        return new InfoboxParameters(cleanTemplate, cleanParameters, Map.of(),
                cleanRendered, document);
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
        if (key == null) return null;
        if (key.rendered()) {
            return renderedFields.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(key.parameter()))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
        }
        return isTemplate(key.template()) ? parameters.get(key.parameter()) : null;
    }

    /**
     * Every value this page retained, under the key a field would use to read it: source
     * parameters as {@code Template.parameter}, rendered rows as {@code Rendered.<label>}.
     * The inverse of {@link #valueOf}; a reader that lists the page's values iterates this
     * rather than spelling the key grammar again (#462).
     */
    public Map<Key, String> values() {
        Map<Key, String> result = new LinkedHashMap<>();
        if (!template.isBlank()) {
            parameters.forEach((name, value) -> {
                if (name != null && !name.isBlank()) result.put(new Key(template, name), value);
            });
        }
        renderedFields.forEach((label, value) -> {
            if (label != null && !label.isBlank()) result.put(Key.renderedRow(label), value);
        });
        return java.util.Collections.unmodifiableMap(result);
    }

    /** The same enumeration as {@link #values()}, retaining links and media. */
    public Map<Key, WikipediaFragment> structuredValues() {
        Map<Key, WikipediaFragment> result = new LinkedHashMap<>();
        if (!template.isBlank()) {
            parameters.forEach((name, value) -> {
                if (name != null && !name.isBlank()) {
                    result.put(new Key(template, name), WikipediaFragment.text(value));
                }
            });
        }
        renderedValues.forEach((label, value) -> {
            if (label != null && !label.isBlank()) result.put(Key.renderedRow(label), value);
        });
        return java.util.Collections.unmodifiableMap(result);
    }

    /**
     * How a field names one infobox value: {@code Infobox film.country} for a source
     * parameter, or {@code Rendered.Group} for a user-visible row.
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

        /** The namespace of rendered infobox rows: {@code Rendered.<label>}. */
        public static final String RENDERED = "Rendered";

        public static Key renderedRow(String label) { return new Key(RENDERED, label); }

        /** Rendered infobox rows use the durable {@code Rendered.<label>} namespace. */
        public boolean rendered() { return template.equalsIgnoreCase(RENDERED); }

        @Override public String toString() { return template + "." + parameter; }
    }
}
