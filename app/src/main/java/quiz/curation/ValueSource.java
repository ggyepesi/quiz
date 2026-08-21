package quiz.curation;

import datasource.evidence.ExtractedClaim;

import java.util.List;

/**
 * Durable provenance of one accepted field value. Entity identity and field-value
 * evidence are deliberately separate: {@code entityId} says which source record was
 * read, while {@code propertyId} says which claim/property produced this value.
 */
public final class ValueSource {
    public String kind;
    public String entityId;
    public String propertyId;
    public String propertyLabel;
    public String direction;
    public String recordUrl;
    /** Structured assertions and exact source fragments accepted for this value. */
    public List<ExtractedClaim> evidence = List.of();

    public ValueSource() { }

    public ValueSource(String kind, String entityId, String propertyId,
                       String recordUrl) {
        this(kind, entityId, propertyId, null, null, recordUrl);
    }

    public ValueSource(String kind, String entityId, String propertyId,
                       String propertyLabel, String direction, String recordUrl) {
        this(kind, entityId, propertyId, propertyLabel, direction, recordUrl, List.of());
    }

    public ValueSource(String kind, String entityId, String propertyId,
                       String propertyLabel, String direction, String recordUrl,
                       List<ExtractedClaim> evidence) {
        this.kind = kind;
        this.entityId = entityId;
        this.propertyId = propertyId;
        this.propertyLabel = propertyLabel;
        this.direction = direction;
        this.recordUrl = recordUrl;
        this.evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public String kind() { return kind; }
    public String entityId() { return entityId; }
    public String propertyId() { return propertyId; }
    public String propertyLabel() { return propertyLabel; }
    public String direction() { return direction; }
    public String recordUrl() { return recordUrl; }
    public List<ExtractedClaim> evidence() {
        return evidence == null ? List.of() : List.copyOf(evidence);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isPresent() {
        return present(kind) || present(entityId) || present(propertyId)
                || present(propertyLabel) || present(direction) || present(recordUrl)
                || !evidence().isEmpty();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
