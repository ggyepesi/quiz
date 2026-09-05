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
    OWNED,

    /** Built offline by grouping records of another modeled class. */
    AGGREGATE;

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

    /** Whether {@link CanonicalSpec#keyFields()} define instance identity. */
    public boolean usesCanonicalKey() {
        return this == STATEMENT;
    }

    /** Whether identity is supplied structurally by owner + production site. */
    public boolean identityFromOwner() {
        return this == OWNED;
    }

    /**
     * What a reader calls this kind.
     *
     * <p>Here rather than in a parallel array of strings beside a combo box, where the
     * mapping was positional: reordering the labels would have silently renamed every
     * kind, because the code asked the combo for an INDEX and compared it to 1, 2 or 3.
     */
    public String label() {
        return switch (this) {
            case SOURCE -> "Source class";
            case STATEMENT -> "Statement class";
            case OWNED -> "Owned class";
            case AGGREGATE -> "Aggregate class";
        };
    }
}
