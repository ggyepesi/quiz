package wikidata.explore.transform;

/**
 * A Transform construct: fill {@code targetType.outField} from a field on the
 * entity a reference points to — {@code out ← via.source}. E.g.
 * {@code Nomination.year} from {@code edition.date} (via = {@code edition},
 * source = {@code date}).
 *
 * <p>Operates on the loaded pool: the referenced entity is already generated (its
 * own fields populated), so this copies the value in memory — no query. The
 * sibling of {@link InvertConstruct}, going forward-down a reference instead of
 * inverting it.
 */
public record ProjectConstruct(
        String targetType,
        String viaField,
        String sourceField,
        String outField) {
}
