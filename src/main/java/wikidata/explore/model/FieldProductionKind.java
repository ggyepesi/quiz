package wikidata.explore.model;

public enum FieldProductionKind {
    INLINE_VALUE,
    DELAYED_ENTITY_FIELD,
    CHILD_OBJECTS,
    /** DERIVED, not fetched: this field is the inverse of a forward reference on
     *  the referenced class — built in memory from data already generated, with no
     *  query. E.g. {@code Category.nominees} = the reverse of
     *  {@code Oscarnominations.categories}. */
    INVERT,
    /** DERIVED, not fetched as a value: a BOOLEAN that is true iff a companion
     *  statement ({@code companionProperty[value, roleQualifier]}) exists on this
     *  record's subject with the same value and role. E.g. {@code Nomination.won} =
     *  a P166/P1346 award-received companion to the P1411/P2453 nomination. Generic
     *  field-value match (see CompanionMatcher); the two PIDs are the only inputs. */
    COMPANION_MATCH,
    /** DERIVED, not fetched: this field's value is copied from a field on the
     *  entity a reference field points to — {@code out ← via.source}. Built in
     *  memory after generation (no query), like {@link #INVERT}: the value lives
     *  on a referenced entity, so once that entity is generated no separate fetch
     *  is needed. E.g. {@code Nomination.year} ← {@code edition.date}
     *  (via = edition, source = date). See {@code ModelProjects}. */
    PROJECT,
    AUTO;

    @Override
    public String toString() {
        return switch (this) {
            case INLINE_VALUE -> "Simple property";
            case DELAYED_ENTITY_FIELD -> "Related entity values";
            case CHILD_OBJECTS -> "Related objects";
            case INVERT -> "Invert (reverse of another field)";
            case COMPANION_MATCH -> "Companion match (outcome flag)";
            case PROJECT -> "From referenced field";
            case AUTO -> "Auto";
        };
    }
}
