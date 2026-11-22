package domain.datasource.api.discovery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One source-native record, with the exact selected seeds its evidence matched. */
public record DiscoveredRelatedRecord(
        String namespace,
        String id,
        String displayName,
        Map<String, List<String>> fields,
        List<String> matchedSeedKeys) {

    public DiscoveredRelatedRecord {
        namespace = required(namespace, "Record namespace is required");
        id = required(id, "Record id is required");
        displayName = displayName == null || displayName.isBlank() ? id : displayName.trim();
        LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
        if (fields != null) fields.forEach((name, values) -> {
            if (name == null || name.isBlank()) return;
            List<String> present = values == null ? List.of() : values.stream()
                    .filter(java.util.Objects::nonNull).map(String::trim)
                    .filter(value -> !value.isBlank()).distinct().toList();
            if (!present.isEmpty()) copy.put(name.trim(), present);
        });
        fields = java.util.Collections.unmodifiableMap(copy);
        matchedSeedKeys = matchedSeedKeys == null ? List.of()
                : matchedSeedKeys.stream().filter(java.util.Objects::nonNull)
                .map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
    }

    public String qualifiedId() { return namespace + ':' + id; }

    private static String required(String value, String message) {
        String clean = value == null ? "" : value.trim();
        if (clean.isBlank()) throw new IllegalArgumentException(message);
        return clean;
    }
}
