package datasource.api.acquisition;

import datasource.EntityRef;

import java.util.List;

/**
 * Provider-neutral bounded set of entities a source operation is asked to fetch.
 *
 * <p>Named a request rather than a selection because a {@code Selection} in this codebase
 * is something a modeller AUTHORS and saves in the model; this is what an authored bound
 * resolves to when a run asks a provider for it. Both classes existed under one name,
 * which forced four fully-qualified references and left no reference telling you which
 * layer it belonged to.
 *
 * <p>The {@link Kind} makes the alternatives mutually exclusive and the constructor
 * enforces it, so "explicit QIDs or a relation, never a silent winner between them" is
 * unrepresentable rather than merely discouraged.
 */
public record PopulationRequest(
        Kind kind,
        String namespace,
        String relationId,
        List<EntityRef> values,
        boolean includeDescendants,
        RelationCondition relationCondition) {

    public enum Kind { RELATION, EXPLICIT }

    public PopulationRequest {
        if (kind == null) throw new IllegalArgumentException("Population kind is required");
        namespace = required(namespace, "Population namespace is required");
        relationId = relationId == null ? "" : relationId.trim();
        values = List.copyOf(values == null ? List.of() : values);
        if (values.isEmpty() && (kind != Kind.RELATION || relationCondition == null)) {
            throw new IllegalArgumentException("Population values are required unless a "
                    + "qualified statement bounds the population");
        }
        if (kind == Kind.RELATION && relationId.isBlank()) {
            throw new IllegalArgumentException("A relation population needs a relation id");
        }
        if (kind == Kind.EXPLICIT && !relationId.isBlank()) {
            throw new IllegalArgumentException("An explicit population has no relation");
        }
        if (kind == Kind.EXPLICIT && relationCondition != null) {
            throw new IllegalArgumentException("An explicit population has no relation condition");
        }
        if (includeDescendants && values.isEmpty()) {
            throw new IllegalArgumentException(
                    "Subclass expansion needs a population relation value");
        }
        if (includeDescendants && relationCondition != null) {
            throw new IllegalArgumentException(
                    "Qualified statement membership does not combine with subclass expansion");
        }
        for (EntityRef value : values) {
            if (value == null || !namespace.equals(value.namespace())) {
                throw new IllegalArgumentException(
                        "Every population value must belong to " + namespace);
            }
        }
        if (relationCondition != null) {
            for (EntityRef value : relationCondition.values()) {
                if (!namespace.equals(value.namespace())) {
                    throw new IllegalArgumentException(
                            "Every relation-condition value must belong to " + namespace);
                }
            }
        }
    }

    public static PopulationRequest relation(
            String namespace, String relationId, List<EntityRef> targets,
            boolean includeDescendants) {
        return new PopulationRequest(
                Kind.RELATION, namespace, relationId, targets, includeDescendants, null);
    }

    public static PopulationRequest qualifiedRelation(
            String namespace, String relationId, List<EntityRef> targets,
            boolean includeDescendants, RelationCondition condition) {
        return new PopulationRequest(
                Kind.RELATION, namespace, relationId, targets, includeDescendants, condition);
    }

    public static PopulationRequest explicit(
            String namespace, List<EntityRef> entities) {
        return new PopulationRequest(Kind.EXPLICIT, namespace, "", entities, false, null);
    }

    private static String required(String value, String message) {
        String clean = value == null ? "" : value.trim();
        if (clean.isBlank()) throw new IllegalArgumentException(message);
        return clean;
    }
}
