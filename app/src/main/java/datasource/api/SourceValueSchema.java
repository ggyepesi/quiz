package datasource.api;

/** Static output contract advertised before an operation is executed. */
public record SourceValueSchema(
        SourceValueKind kind,
        boolean collection,
        String referenceNamespace) {

    public SourceValueSchema {
        kind = kind == null ? SourceValueKind.UNKNOWN : kind;
        referenceNamespace = referenceNamespace == null ? "" : referenceNamespace.trim();
    }

    public static SourceValueSchema collection(SourceValueKind kind) {
        return new SourceValueSchema(kind, true, "");
    }
}
