package wikidata.explore.transform;

/**
 * A declarative rule for filling a missing field by <em>following a reference
 * field</em> to its target entity and reading a direct property off it — e.g.
 * {@code Nomination.year} from its {@code edition}'s {@code point in time
 * (P585)}. The simpler sibling of {@link FieldFallbackRule} (which joins on a
 * related entity's statement + qualifier); here the target is already referenced
 * by the instance, so it's a straight lookup.
 *
 * @param className the member class whose instances are filled (e.g. "Nomination")
 * @param field     the field to fill (e.g. "year")
 * @param viaField  the reference field to follow (e.g. "edition")
 * @param property  the direct property to read off the target (e.g. "P585")
 * @param extract   how to lift the value
 * @param origin    provenance stamped on emitted corrections
 */
public record ReferenceLookupRule(
        String className,
        String field,
        String viaField,
        String property,
        FieldFallbackRule.Extract extract,
        String origin) {

    /** Recover an Oscar nomination's year from its ceremony edition's date — the
     *  authoritative source (every missing-year nomination carries an edition). */
    public static ReferenceLookupRule oscarEditionYear() {
        return new ReferenceLookupRule(
                "Nomination", "year", "edition", "P585",
                FieldFallbackRule.Extract.YEAR, "wikidata-edition");
    }
}
