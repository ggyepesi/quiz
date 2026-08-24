package datasource.api.acquisition;

import datasource.EntityRef;

import java.util.List;

/** Provider-neutral logical population selected by a configured source operation. */
public record PopulationSelection(
        Kind kind,
        String namespace,
        String relationId,
        List<EntityRef> values,
        boolean includeDescendants) {

    public enum Kind { RELATION, EXPLICIT }

    public PopulationSelection {
        if (kind == null) throw new IllegalArgumentException("Population kind is required");
        namespace = required(namespace, "Population namespace is required");
        relationId = relationId == null ? "" : relationId.trim();
        values = List.copyOf(values == null ? List.of() : values);
        if (values.isEmpty()) throw new IllegalArgumentException("Population values are required");
        if (kind == Kind.RELATION && relationId.isBlank()) {
            throw new IllegalArgumentException("A relation population needs a relation id");
        }
        if (kind == Kind.EXPLICIT && !relationId.isBlank()) {
            throw new IllegalArgumentException("An explicit population has no relation");
        }
        for (EntityRef value : values) {
            if (value == null || !namespace.equals(value.namespace())) {
                throw new IllegalArgumentException(
                        "Every population value must belong to " + namespace);
            }
        }
    }

    public static PopulationSelection relation(
            String namespace, String relationId, List<EntityRef> targets,
            boolean includeDescendants) {
        return new PopulationSelection(
                Kind.RELATION, namespace, relationId, targets, includeDescendants);
    }

    public static PopulationSelection explicit(
            String namespace, List<EntityRef> entities) {
        return new PopulationSelection(Kind.EXPLICIT, namespace, "", entities, false);
    }

    private static String required(String value, String message) {
        String clean = value == null ? "" : value.trim();
        if (clean.isBlank()) throw new IllegalArgumentException(message);
        return clean;
    }
}
