package datasource;

import java.net.URI;
import java.util.Optional;

/** One external source record and, optionally, the property read from it. */
public record SourceRef(
        String kind, String sourceId, String recordUrl, String propertyId,
        String propertyLabel, String direction) {
    public SourceRef {
        kind = text(kind);
        sourceId = text(sourceId);
        recordUrl = text(recordUrl);
        propertyId = text(propertyId);
        propertyLabel = text(propertyLabel);
        direction = text(direction);
        if (kind.isBlank()) throw new IllegalArgumentException("Source kind is required");
        if (sourceId.isBlank() && recordUrl.isBlank()) {
            throw new IllegalArgumentException("Source identifier or URL is required");
        }
        if (!recordUrl.isBlank()) URI.create(recordUrl);
    }

    public SourceRef(String kind, String sourceId, String recordUrl) {
        this(kind, sourceId, recordUrl, null, null, null);
    }

    public SourceRef(String kind, String sourceId, String recordUrl, String propertyId) {
        this(kind, sourceId, recordUrl, propertyId, null, null);
    }

    public Optional<EntityRef> entityRef() {
        return sourceId.isBlank()
                ? Optional.empty()
                : Optional.of(new EntityRef(kind.toLowerCase(), sourceId));
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
