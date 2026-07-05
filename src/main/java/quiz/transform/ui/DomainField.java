package quiz.transform.ui;

/**
 * One field of a domain class, as derived from a loaded snapshot's
 * {@link quiz.transform.app.DomainSchema} — the domain-wide pool an operation
 * picks its arguments from. A field belongs to a class ({@code type}) and is
 * either a reference (entity-valued), a collection, or a scalar; those shapes are
 * what {@link OperationSignature} slots filter on.
 */
public record DomainField(String type, String field,
                          boolean reference, boolean collection) {

    public boolean scalar() {
        return !reference && !collection;
    }

    /** {@code Type.field} — the qualified label shown in the fields pane. */
    public String path() {
        return type + "." + field;
    }

    @Override
    public String toString() {
        String shape = reference ? (collection ? "ref[]" : "ref")
                : collection ? "[]" : "value";
        return path() + "  (" + shape + ")";
    }
}
