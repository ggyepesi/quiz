package datasource.api.discovery;

import java.util.Map;

/** One source-native possibility observed in a discovery sample. */
public record DiscoveredSourceValue(
        String value,
        int have,
        String examples,
        Map<String, String> metadata) {

    public DiscoveredSourceValue {
        value = text(value);
        have = Math.max(0, have);
        examples = text(examples);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public DiscoveredSourceValue(String value, int have, String examples) {
        this(value, have, examples, Map.of());
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
