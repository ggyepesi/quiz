package domain.datasource.api;

import java.util.List;

/**
 * One provider-owned, read-only piece of a configured recipe.
 *
 * <p>The configuration tree asks the operation for these elements instead of
 * decoding provider parameters. A provider whose grammar is structural may return a
 * hierarchy; the default operation projection exposes its ordinary declared
 * parameters.</p>
 */
public record ConfigurationElement(String key, String caption,
        List<ConfigurationElement> children) {

    public ConfigurationElement {
        key = required(key, "Configuration element key");
        caption = required(caption, "Configuration element caption");
        children = children == null ? List.of() : List.copyOf(children);
    }

    public ConfigurationElement(String key, String caption) {
        this(key, caption, List.of());
    }

    private static String required(String value, String name) {
        String clean = value == null ? "" : value.trim();
        if (clean.isBlank()) throw new IllegalArgumentException(name + " is required");
        return clean;
    }
}
