package domain.config;

public enum FieldCardinality {
    SINGLE,
    COLLECTION;

    /** Older saved models used AUTO even though every runtime consumer treated it
     * as SINGLE. Accept that spelling only at the JSON boundary; it is no longer a
     * model state. */
    @com.fasterxml.jackson.annotation.JsonCreator
    public static FieldCardinality fromJson(String value) {
        return value == null || value.isBlank() || "AUTO".equalsIgnoreCase(value)
                ? SINGLE : valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }

    public boolean isCollection() {
        return this == COLLECTION;
    }

    /** Providers may supply several values even for a single-valued field.
     * Keep the first value that can fill the declared field, in source order. */
    public static <T, E extends Exception> T firstMapped(Object raw, ValueMapping<T, E> map)
            throws E {
        if (!(raw instanceof Iterable<?> values)) return map.apply(raw);
        for (Object value : values) {
            T mapped = map.apply(value);
            if (mapped != null) return mapped;
        }
        return null;
    }

    @FunctionalInterface
    public interface ValueMapping<T, E extends Exception> {
        T apply(Object value) throws E;
    }

    @Override
    public String toString() {
        return switch (this) {
            case SINGLE -> "Single value";
            case COLLECTION -> "List";
        };
    }
}
