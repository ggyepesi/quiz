package datasource.graph;

/** One configured edge graph expansion may follow without requiring a StatementClass. */
public record GraphTraversalStep(
        String id,
        String sourceNodeClass,
        String targetNodeClass,
        String sourceField,
        GraphRelation relation,
        GraphTraversalDirection direction,
        GraphExpansionPolicy policy) {
    public GraphTraversalStep {
        id = required(id, "Step id is required");
        sourceNodeClass = required(sourceNodeClass, "Source node class is required");
        targetNodeClass = required(targetNodeClass, "Target node class is required");
        sourceField = required(sourceField, "Source field is required");
        if (relation == null) throw new IllegalArgumentException("Relation is required");
        if (direction == null) throw new IllegalArgumentException("Direction is required");
        policy = policy == null ? GraphExpansionPolicy.NONE : policy;
    }
    private static String required(String value, String message) {
        String clean = value == null ? "" : value.trim();
        if (clean.isBlank()) throw new IllegalArgumentException(message);
        return clean;
    }
}
