package wikidata.explore.transform;

import java.util.List;

/**
 * A Load-time construct: load a property's STATEMENTS (not just the direct
 * claim) with their qualifiers, as nested statement objects on each entity.
 *
 * <p>The direct claim {@code ?film wdt:P166 ?award} loses everything but the
 * award. The statement path {@code ?film p:P166 ?st . ?st ps:P166 ?award} lets
 * us also read {@code ?st pq:P585 ?year} (ceremony year), {@code pq:P1686 ?for}
 * (for-work). This config drives {@link QualifierLoader}, which attaches one
 * {@code statementType} object per statement under {@code entityType.statementField},
 * holding the main value under {@code valueField} plus each qualifier.
 *
 * <p>e.g. {@code QualifierLoadConfig("Film", "P166", "awards", "AwardStatement",
 * "award", [P585→year YEAR, P1686→forWork ENTITY])}. A {@code reify(promote)}
 * construct then lifts those statements into top-level {@code Nomination} events.
 */
public record QualifierLoadConfig(
        String entityType,
        String propertyPid,
        String statementField,
        String statementType,
        String valueField,
        String valueTypeQid,
        List<Qualifier> qualifiers) {

    /** Back-compat 6-arg form (no value-type filter). */
    public QualifierLoadConfig(String entityType, String propertyPid,
            String statementField, String statementType, String valueField,
            List<Qualifier> qualifiers) {
        this(entityType, propertyPid, statementField, statementType, valueField,
                "", qualifiers);
    }

    /** When set (e.g. Q19020 "Academy Awards"), keep only statements whose main
     *  value is {@code wdt:P31} this type — e.g. only Oscar categories, dropping
     *  every other award a winner also received. */
    public boolean hasValueType() {
        return valueTypeQid != null && valueTypeQid.matches("Q\\d+");
    }

    public enum Kind {
        /** Resolve the qualifier to a labelled entity (e.g. P1686 for-work). */
        ENTITY,
        /** A time qualifier reduced to its 4-digit year (e.g. P585). */
        YEAR,
        /** A raw literal qualifier kept as a string. */
        STRING
    }

    /**
     * @param multi the qualifier repeats on one statement (e.g. P2453 nominee for a
     *              shared award lists every co-nominee) — collect ALL values into a
     *              list instead of keeping one. Driven by the model field's
     *              "Count: List" cardinality.
     */
    public record Qualifier(String pid, String fieldName, Kind kind, boolean multi) {
        /** Back-compat single-valued form. */
        public Qualifier(String pid, String fieldName, Kind kind) {
            this(pid, fieldName, kind, false);
        }
    }

    public boolean valid() {
        return entityType != null && !entityType.isBlank()
                && propertyPid != null && propertyPid.matches("P\\d+")
                && statementField != null && !statementField.isBlank();
    }
}
