package datasource.evidence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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
}
