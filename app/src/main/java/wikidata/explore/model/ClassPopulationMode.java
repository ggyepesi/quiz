package wikidata.explore.model;

/** Where a source class's members come from. */
public enum ClassPopulationMode {
    /** The established rule: use the class's own source, or inherit its base's source. */
    GENERATED,
    /** A saved transformation is the sole producer; base membership is schema only. */
    MATERIALIZED_TRANSFORM
}
