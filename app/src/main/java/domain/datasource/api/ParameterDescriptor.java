package domain.datasource.api;

import java.util.List;

/** One declarative input rendered by shared ModelBuilder/Transform configuration UI. */
public record ParameterDescriptor(
        String key,
        String label,
        Kind kind,
        boolean required,
        String defaultValue,
        List<String> options,
        String help,
        SourceReferenceSchema referenceSchema,
        boolean advanced) {

    public enum Kind {
        TEXT, INTEGER, BOOLEAN, CHOICE, DISCOVERED_VALUE, REFERENCE,
        /** A finite ordered list of graph routes, edited structurally rather than as JSON. */
        RELATION_ROUTES
    }

    public ParameterDescriptor(
            String key, String label, Kind kind, boolean required,
            String defaultValue, List<String> options, String help) {
        this(key, label, kind, required, defaultValue, options, help, null, false);
    }

    public ParameterDescriptor(String key, String label, Kind kind, boolean required,
            String defaultValue, List<String> options, String help, SourceReferenceSchema referenceSchema) {
        this(key, label, kind, required, defaultValue, options, help, referenceSchema, false);
    }

    public ParameterDescriptor {
        key = text(key);
        label = text(label);
        if (key.isBlank()) throw new IllegalArgumentException("Parameter key is required");
        if (label.isBlank()) label = key;
        kind = kind == null ? Kind.TEXT : kind;
        defaultValue = text(defaultValue);
        options = options == null ? List.of() : List.copyOf(options);
        help = text(help);
        if (kind == Kind.REFERENCE && referenceSchema == null) {
            throw new IllegalArgumentException(
                    "Reference parameter '" + key + "' requires a reference schema");
        }
        if (kind != Kind.REFERENCE && referenceSchema != null) {
            throw new IllegalArgumentException(
                    "Only reference parameters may declare a reference schema: " + key);
        }
        if (advanced && required) {
            throw new IllegalArgumentException("Required parameter '" + key + "' belongs in the main configuration");
        }
    }

    public ParameterDescriptor inAdvancedSettings() {
        return new ParameterDescriptor(key, label, kind, required, defaultValue, options, help, referenceSchema, true);
    }

    public String effectiveValue(String value) {
        String configured = text(value);
        return configured.isEmpty() ? defaultValue : configured;
    }

    public boolean usesDefault(String value) {
        String effective = effectiveValue(value);
        if (effective.equals(defaultValue)) return true;
        if (kind == Kind.BOOLEAN) return effective.equalsIgnoreCase(defaultValue);
        if (kind == Kind.INTEGER) {
            try {
                return Integer.parseInt(effective) == Integer.parseInt(defaultValue);
            } catch (NumberFormatException invalidOrBlank) {
                return false;
            }
        }
        return false;
    }

    public static ParameterDescriptor reference(
            String key, String label, boolean required, String defaultValue,
            String help, SourceReferenceSchema schema) {
        return new ParameterDescriptor(key, label, Kind.REFERENCE, required,
                defaultValue, List.of(), help, schema);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
