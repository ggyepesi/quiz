package datasource.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A model's binding to one operation offered by a datasource.
 *
 * <p>The catalogue says what can be configured; this value is the configuration:
 * provider + operation + normalized parameters. It intentionally contains no runtime
 * client or query object, so it can live in a persisted model.
 */
public record SourceOfferingBinding(
        String providerId,
        String operationId,
        Map<String, String> parameters) {

    public SourceOfferingBinding {
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

    private static String cleanRequired(String value, String what) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException(what + " is required");
        return clean;
    }
}
