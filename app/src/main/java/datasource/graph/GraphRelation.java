package datasource.graph;

/** Provider-owned label of a directed graph edge. */
public record GraphRelation(String providerId, String relationId) {
    public GraphRelation {
        providerId = required(providerId, "Provider id is required");
        relationId = required(relationId, "Relation id is required");
    }

    private static String required(String value, String message) {
        String clean = value == null ? "" : value.trim();
        if (clean.isBlank()) throw new IllegalArgumentException(message);
        return clean;
    }
}
