package quiz.transform.ui;

import objectview.field.FieldPath;
import objectview.field.FieldKind;

/**
 * One field of a domain class — the domain-wide pool an operation picks its
 * arguments from. The path is a first-class {@link FieldPath} (dotted segments +
 * title + optional leaf {@code Field}), not a raw string, so nested structure is
 * carried, not re-parsed. A field belongs to a class ({@code type}) and is either a
 * reference (entity-valued), a collection, or a scalar; those shapes are what the
 * operation pickers filter on. The {@link #kind} is the value shape
 * (boolean / ordered / text / reference / collection), populated by the {@code
 * DomainModel} that knows the field's type — so operator/facet choices don't have
 * to re-sample the instances.
 */
public record DomainField(String type, FieldPath fieldPath,
                          boolean reference, boolean collection, FieldKind kind) {

    /** Shape only — {@code kind} defaults from the shape (COLLECTION / REFERENCE),
     *  else UNKNOWN for a scalar whose value type the caller doesn't know. */
    public DomainField(String type, FieldPath fieldPath, boolean reference, boolean collection) {
        this(type, fieldPath, reference, collection,
                collection ? FieldKind.COLLECTION
                        : reference ? FieldKind.REFERENCE : FieldKind.UNKNOWN);
    }

    /** Convenience: a field from a dotted path string (shape-defaulted kind). */
    public DomainField(String type, String dottedPath, boolean reference, boolean collection) {
        this(type, FieldPath.parse(dottedPath), reference, collection);
    }

    /** Convenience: a dotted path with an explicit value {@code kind}. */
    public DomainField(String type, String dottedPath, boolean reference,
                       boolean collection, FieldKind kind) {
        this(type, FieldPath.parse(dottedPath), reference, collection, kind);
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

    /** {@code Type.field} for display: reserved contract keys (e.g. {@code @view:display})
     *  are shown by their human label ("Name"), so filter/group descriptions read naturally
     *  instead of leaking the raw key. */
    public String displayPath() {
        StringBuilder sb = new StringBuilder(type);
        for (String segment : fieldPath.segments()) {
            sb.append('.').append(
                    objectview.field.ViewableContractFieldSet.label(segment));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        String shape = reference ? (collection ? "ref[]" : "ref")
                : collection ? "[]" : "value";
        return path() + "  (" + shape + ")";
    }
}
