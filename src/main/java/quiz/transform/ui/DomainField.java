package quiz.transform.ui;

import quiz.QuizableFieldPaths.FieldPath;

/**
 * One field of a domain class — the domain-wide pool an operation picks its
 * arguments from. The path is a first-class {@link FieldPath} (dotted segments +
 * title + optional leaf {@code Field}), not a raw string, so nested structure is
 * carried, not re-parsed. A field belongs to a class ({@code type}) and is either a
 * reference (entity-valued), a collection, or a scalar; those shapes are what
 * {@link OperationSignature} slots filter on.
 */
public record DomainField(String type, FieldPath fieldPath,
                          boolean reference, boolean collection) {

    /** Convenience: a field from a dotted path string. */
    public DomainField(String type, String dottedPath, boolean reference, boolean collection) {
        this(type, FieldPath.of(dottedPath), reference, collection);
    }

    /** The dotted access path, e.g. {@code nominee.name} — used by FieldAccess/facets. */
    public String field() {
        return fieldPath.dotted();
    }

    public boolean scalar() {
        return !reference && !collection;
    }

    /** {@code Type.field} — the qualified label shown in the fields pane. */
    public String path() {
        return type + "." + fieldPath.dotted();
    }

    @Override
    public String toString() {
        String shape = reference ? (collection ? "ref[]" : "ref")
                : collection ? "[]" : "value";
        return path() + "  (" + shape + ")";
    }
}
