package wikidata.explore.model;

/**
 * How a DATE-typed statement qualifier is projected into the generated model.
 *
 * <p>{@link #YEAR} is the migration default: models written before full qualifier
 * dates existed deliberately reduced time qualifiers to their year. New fields
 * may opt into {@link #DATE}, which retains the source precision and calendar.</p>
 */
public enum QualifierDateMode {
    YEAR,
    DATE
}
