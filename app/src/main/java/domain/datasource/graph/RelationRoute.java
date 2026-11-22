package domain.datasource.graph;

import java.util.List;

/**
 * One finite, authored route from a field owner to a related entity.
 *
 * <p>The route is provider-neutral: each step names the provider that owns its
 * relation. It contains no repetition or implicit closure; a longer traversal is
 * represented by more explicit steps. Providers prepare their persisted recipe into
 * this type once, and the common executor consumes only this typed form.</p>
 */
public record RelationRoute(String id, String label, List<Step> steps) {
    public RelationRoute {
        id = required(id, "Route id is required");
        label = label == null || label.isBlank() ? id : label.trim();
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("A relation route needs at least one step");
        }
        if (steps.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Relation-route steps cannot be null");
        }
    }

    public record Step(GraphRelation relation, GraphTraversalDirection direction) {
        public Step {
            if (relation == null) throw new IllegalArgumentException("Relation is required");
            if (direction == null) throw new IllegalArgumentException("Direction is required");
        }
    }

    private static String required(String value, String message) {
        String clean = value == null ? "" : value.trim();
        if (clean.isBlank()) throw new IllegalArgumentException(message);
        return clean;
    }
}
