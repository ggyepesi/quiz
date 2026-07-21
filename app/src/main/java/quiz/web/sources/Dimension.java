package quiz.web.sources;

/**
 * A DECLARED groupable dimension for LIVE re-faceting: the user flips among a served
 * type's dimensions and the view groups on demand. Grouping-as-capability is declared
 * here (model-derived candidates, later transform-curatable); grouping-as-execution is
 * the view building a {@link objectview.facet.Facet} from this at serve time.
 *
 * @param label display name of the dimension (e.g. "type", "genre", "category")
 * @param path  the dotted field path the value is read from (e.g. "nominee.type")
 * @param kind  how it buckets — a reference entity, a scalar value, or a boolean
 */
public record Dimension(String label, String path, Kind kind) {
    public enum Kind { REFERENCE, VALUE, BOOLEAN }
}
