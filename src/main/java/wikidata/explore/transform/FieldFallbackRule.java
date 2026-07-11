package wikidata.explore.transform;

import java.util.List;

/**
 * A declarative rule for filling a missing field from a <em>redundant</em> Wikidata
 * statement on a related entity — the general form of "the year is also on the
 * award-received statement." Data, not code: one rule is one configured fix,
 * executed by {@link PropertyFallbackCorrectionSource}, and it's the unit a
 * modelbuilder can discover (via a coverage/agreement probe) and persist per
 * (class, field).
 *
 * <p>Semantics: for each instance of {@link #className} that passes the optional
 * {@link #gateField}={@link #gateValue} gate and is missing {@link #field}, consult
 * the related entities named by {@link #fallbackVia} (in order). On each, read the
 * statement {@code entity <property> ?v} whose value equals the instance's
 * {@link #joinField} entity, and take its {@link #valueQualifier} qualifier,
 * {@link #extract}ed. A single unambiguous value fills the field (origin
 * {@link #origin}); zero or many are left for curation.
 *
 * @param className      the member class whose instances are filled (e.g. "Nomination")
 * @param field          the field to fill (e.g. "year")
 * @param gateField      optional precondition field (e.g. "won"); null = no gate
 * @param gateValue      the value {@code gateField} must equal (e.g. Boolean.TRUE)
 * @param fallbackVia    related-entity fields to consult, in preference order
 *                       (e.g. ["forWork", "nominee"])
 * @param property       the statement property on the related entity (e.g. "P166")
 * @param joinField      the instance field whose entity must equal the statement's
 *                       value — the match key (e.g. "category")
 * @param valueQualifier the qualifier holding the value to lift (e.g. "P585")
 * @param extract        how to read the qualifier value
 * @param origin         provenance stamped on emitted corrections (e.g. "wikidata-award")
 */
public record FieldFallbackRule(
        String className,
        String field,
        String gateField,
        Object gateValue,
        List<String> fallbackVia,
        String property,
        String joinField,
        String valueQualifier,
        Extract extract,
        String origin) {

    /** How to lift a value from the qualifier binding. */
    public enum Extract {
        /** The 4-digit year of a time value — {@code YEAR(?val)}. */
        YEAR,
        /** The value's string form — {@code STR(?val)}. */
        LITERAL
    }

    /** The verified first instance: an Oscar nomination's missing year, shared from
     *  the winner's {@code award received (P166) → point in time (P585)}, matched by
     *  category. Recovers the 149 winner gaps (142 unambiguously). */
    public static FieldFallbackRule oscarYear() {
        return new FieldFallbackRule(
                "Nomination", "year",
                "won", Boolean.TRUE,
                List.of("forWork", "nominee"),
                "P166", "category", "P585",
                Extract.YEAR, "wikidata-award");
    }
}
