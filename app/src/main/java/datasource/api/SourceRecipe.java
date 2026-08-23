package datasource.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Durable configuration of one operation offered by a datasource.
 *
 * <p>The catalogue describes what may be configured; a recipe records one choice:
 * provider + operation + normalized parameters. Scope belongs to the place which binds
 * the recipe (class population, class names, field value, evidence), not to this value,
 * so the same contract works throughout ModelBuilder and Transform without importing
 * either application's model.
 *
 * <p>It contains no runtime client or query. Execution resolves the pair through a
 * {@link DatasourceRegistry}, which makes an unavailable provider/operation a visible
 * composition error instead of serialized implementation state.
 */
public record SourceRecipe(
        String providerId,
        String operationId,
        Map<String, String> parameters) {

    public SourceRecipe {
        providerId = cleanRequired(providerId, "provider id");
        operationId = cleanRequired(operationId, "operation id");
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        if (parameters != null) {
            parameters.forEach((key, value) -> {
                if (key != null && !key.isBlank()) {
                    copy.put(key.trim(), value == null ? "" : value.trim());
                }
            });
        }
        parameters = Map.copyOf(copy);
    }

    public String parameter(String name) {
        return parameters.getOrDefault(name, "");
    }

    /** Resolve the configured offering, failing with the registry's diagnostic. */
    public DatasourceOperation resolve(DatasourceRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("Datasource registry is required");
        }
        return registry.require(providerId, operationId, DatasourceOperation.class);
    }

    /** Resolve and verify that this recipe is being attached at the right scope. */
    public DatasourceOperation resolve(
            DatasourceRegistry registry, BindingScope expectedScope) {
        DatasourceOperation operation = resolve(registry);
        if (expectedScope != null && operation.scope() != expectedScope) {
            throw new IllegalArgumentException(
                    "Datasource recipe " + providerId + "." + operationId
                            + " has scope " + operation.scope() + ", not "
                            + expectedScope);
        }
        return operation;
    }

    private static String cleanRequired(String value, String what) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException(what + " is required");
        return clean;
    }
}
