package wikidata.explore.model;

/**
 * A per-field expectation checked AFTER the transform stage (for reified/statement
 * classes). Separates "the field should be present" (a check) from "drop rows that
 * fail" (an action), so a field can be audited without deleting data.
 *
 * <ul>
 *   <li>{@code NONE} — no check (default).</li>
 *   <li>{@code EXPECTED} — keep every row, but COUNT + COLLECT the ones missing the
 *       field (a coverage report + a present/missing facet), so the gap is visible
 *       for curation without a hand-built transform filter.</li>
 *   <li>{@code REQUIRED} — DROP rows missing the field (the strict form; use only
 *       once EXPECTED has shown the missing rows are genuinely bad data — an absent
 *       qualifier is often a legit record, e.g. a nomination with no ceremony QID).</li>
 * </ul>
 */
public enum FieldExpectation {
    NONE,
    EXPECTED,
    REQUIRED
}
