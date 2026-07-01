package wikidata.explore.transform;

/**
 * A Transform construct: invert a reference. From {@code sourceType.refField}
 * (e.g. {@code Film.awards}) build {@code targetType.backRefField} (e.g.
 * {@code Award.films}) — turning a forward reference into the grouped reverse.
 *
 * <p>Operates on the loaded snapshot pool: the referenced objects are already in
 * the pool, so inverting just stamps them with {@code targetType} and gives each
 * a back-reference list pointing at the sources that referenced it.
 */
public record InvertConstruct(
        String sourceType,
        String refField,
        String targetType,
        String backRefField) {
}
