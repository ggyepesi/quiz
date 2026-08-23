package datasource.api;

/** Where a datasource operation may be bound in a domain model. */
public enum BindingScope {
    CLASS_IDENTITY,
    CLASS_POPULATION,
    CLASS_NAMES,
    FIELD_VALUE,
    SOURCE_CORRESPONDENCE,
    DOCUMENT_EVIDENCE
}
