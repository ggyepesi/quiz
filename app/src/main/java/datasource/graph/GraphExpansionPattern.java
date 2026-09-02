package datasource.graph;

/**
 * One labelled-edge discovery pattern and its domain materialization.
 * Provider-specific configuration is represented by {@link GraphRelation}; the graph
 * contract itself knows neither QIDs nor PIDs.
 */
public record GraphExpansionPattern(
        String id,
        String sourceNodeClass,
        String targetNodeClass,
        GraphRelation relation,
        String statementClass,
        String sourceField,
        String targetField,
        GraphTraversalDirection direction) implements GraphEdgeDefinition {

    public GraphExpansionPattern {
        id = required(id, "Pattern id is required");
        sourceNodeClass = required(sourceNodeClass, "Source node class is required");
        targetNodeClass = required(targetNodeClass, "Target node class is required");
        if (relation == null) throw new IllegalArgumentException("Relation is required");
        statementClass = required(statementClass, "Statement class is required");
        sourceField = required(sourceField, "Source field is required");
        targetField = required(targetField, "Target field is required");
        // Saved patterns from before direction became intrinsic omit the property;
        // the only pattern that existed then was reverse/incoming expansion.
        direction = direction == null ? GraphTraversalDirection.INCOMING : direction;
    }

    private static String required(String value, String message) {
        String clean = value == null ? "" : value.trim();
        if (clean.isBlank()) throw new IllegalArgumentException(message);
        return clean;
    }
}
