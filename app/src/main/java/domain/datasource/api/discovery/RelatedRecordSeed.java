package domain.datasource.api.discovery;

import domain.datasource.EntityRef;

/** One exact selected instance handed to a related-record discovery operation. */
public record RelatedRecordSeed(String key, EntityRef entity, String displayName) {
    public RelatedRecordSeed {
        key = required(key, "Seed key is required");
        if (entity == null) throw new IllegalArgumentException("Seed entity is required");
        displayName = displayName == null || displayName.isBlank()
                ? entity.id() : displayName.trim();
    }

    private static String required(String value, String message) {
        String clean = value == null ? "" : value.trim();
        if (clean.isBlank()) throw new IllegalArgumentException(message);
        return clean;
    }
}
