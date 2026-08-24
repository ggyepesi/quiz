package datasource.api;

/** Where a datasource operation may be bound in a domain model. */
public enum BindingScope {
    CLASS_IDENTITY,
    CLASS_POPULATION,
    CLASS_NAMES,
    FIELD_VALUE,
    /** Reserved for an explicit cross-source identity/join declaration. No standard
     * provider offers one yet; keeping the scope makes that future contract distinct
     * from class identity rather than encoding joins as field recipes. */
    SOURCE_CORRESPONDENCE,
    /** Evidence attached to a retrieved source document rather than a model field. */
    DOCUMENT_EVIDENCE
}
