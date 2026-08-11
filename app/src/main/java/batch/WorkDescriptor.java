package batch;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializable description of one exact work partition. {@code type} selects the caller's
 * {@link WorkUnitFactory}; {@code parameters} contain everything it needs to rebuild the
 * executable unit after a process restart.
 */
public record WorkDescriptor(
        String type,
        String key,
        String title,
        Map<String, String> parameters) {

    public WorkDescriptor {
        type = requireText(type, "type");
        key = requireText(key, "key");
        title = requireText(title, "title");
        parameters = parameters == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(parameters));
    }

    public WorkDescriptor(String type, String key, String title) {
        this(type, key, title, Map.of());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
