package quiz.web.sources;

/**
 * An explicitly configured groupable dimension for LIVE re-faceting: the user flips
 * among a served type's dimensions and the view groups on demand. Merely having a field
 * never creates one; Transform must declare the grouping operation.
 *
 * @param label display name of the dimension (e.g. "type", "genre", "category")
 * @param path  the dotted field path the value is read from (e.g. "nominee.type")
 * @param kind  how it buckets — a reference entity, a scalar value, or a boolean
 */
public record Dimension(String label, String path, Kind kind) {
    public enum Kind { REFERENCE, VALUE, BOOLEAN }
}
