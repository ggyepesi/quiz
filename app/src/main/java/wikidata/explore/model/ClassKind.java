package wikidata.explore.model;

/**
 * Explicit construction path for a modeled class — and, because the two are the same
 * question, where its instances get their identity.
 *
 * <p>How a class is built decides what makes two of its instances the same instance, so
 * a second discriminator could only ever agree with this one or be wrong. There was one:
 * {@code CanonicalSpec.Kind}, chosen separately in the editor, set to DERIVED by three
 * places that all keyed on the class reifying statements, and corrected by hand for
 * owned classes at the one point that asked whether an instance has an identity of its
 * own.
 */
public enum ClassKind {

    /** Populated from a datasource, and identified by the id that source gives it. */
    SOURCE,

    /** Reified from statements, and identified by the statement or a declared key over
     *  the fields it carries. */
    STATEMENT,

    /** Produced per owning instance, and identified by its owner and the site that made
     *  it — never by anything of its own. */
    OWNED;

    /**
     * Whether instances take their identity from the datasource, rather than deriving it.
     *
     * <p>Only a SOURCE class does. A part borrows its owner's identifier so that its
     * fields can load from the owner's QID, which made it look like an entity to anything
     * reading the id alone — and is why the identity question had to be corrected for
     * owned classes wherever it was asked.
     */
    public boolean identityFromSource() {
        return this == SOURCE;
    }
}
