package quiz.curation;

/**
 * Durable provenance of one accepted field value. Entity identity and field-value
 * evidence are deliberately separate: {@code entityId} says which source record was
 * read, while {@code propertyId} says which claim/property produced this value.
 */
public final class ValueSource {
    public String kind;
    public String entityId;
    public String propertyId;
    public String recordUrl;

    public ValueSource() { }

    public ValueSource(String kind, String entityId, String propertyId,
                       String recordUrl) {
        this.kind = kind;
        this.entityId = entityId;
        this.propertyId = propertyId;
        this.recordUrl = recordUrl;
    }

    public String kind() { return kind; }
    public String entityId() { return entityId; }
    public String propertyId() { return propertyId; }
    public String recordUrl() { return recordUrl; }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isPresent() {
        return present(kind) || present(entityId) || present(propertyId) || present(recordUrl);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
