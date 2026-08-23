package datasource.api;

/** Provider-neutral contract for a parameter that names source records/properties. */
public record SourceReferenceSchema(
        String namespace,
        Kind kind,
        boolean collection) {

    public enum Kind { ENTITY, PROPERTY, RECORD }

    public SourceReferenceSchema {
        namespace = namespace == null ? "" : namespace.trim();
        if (namespace.isBlank()) {
            throw new IllegalArgumentException("Reference namespace is required");
        }
        kind = kind == null ? Kind.RECORD : kind;
    }
}
