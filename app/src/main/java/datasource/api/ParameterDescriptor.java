package datasource.api;

import java.util.List;

/** One declarative input rendered by shared ModelBuilder/Transform configuration UI. */
public record ParameterDescriptor(
        String key,
        String label,
        Kind kind,
        boolean required,
        String defaultValue,
        List<String> options,
        String help) {

    public enum Kind { TEXT, INTEGER, BOOLEAN, CHOICE, DISCOVERED_VALUE }

    public ParameterDescriptor {
        key = text(key);
        label = text(label);
        if (key.isBlank()) throw new IllegalArgumentException("Parameter key is required");
        if (label.isBlank()) label = key;
        kind = kind == null ? Kind.TEXT : kind;
        defaultValue = text(defaultValue);
        options = options == null ? List.of() : List.copyOf(options);
        help = text(help);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
