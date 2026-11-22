package domain.datasource.api.discovery;

import domain.datasource.api.SourceValueKind;

/** One declared field of the source records a discovery operation returns. */
public record DiscoveredRecordField(
        String name, String label, SourceValueKind kind, boolean collection) {
    public DiscoveredRecordField {
        name = required(name, "Discovered-record field name is required");
        label = label == null || label.isBlank() ? name : label.trim();
        kind = kind == null ? SourceValueKind.TEXT : kind;
        if (!kind.bindableToField()) {
            throw new IllegalArgumentException(
                    "A discovered-record field must be renderable: " + name);
        }
    }

    private static String required(String value, String message) {
        String clean = value == null ? "" : value.trim();
        if (clean.isBlank()) throw new IllegalArgumentException(message);
        return clean;
    }
}
