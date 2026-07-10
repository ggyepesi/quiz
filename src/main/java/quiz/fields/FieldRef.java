package quiz.fields;


/**
 * One field of a domain object — its name, value shape and display type — regardless
 * of whether the object is a reflected {@link quiz.Quizable} (declared Java fields)
 * or a dynamic property map ({@link quiz.DynamicFields}). The rendering / search /
 * sort / config machinery reads this instead of re-deriving field metadata two ways.
 *
 * <p>Traversal into a reference is composition, not a method here: read the value and
 * wrap it — {@code FieldSet.of((Quizable) set.read("nominee"))} — mirroring {@code
 * FieldAccess.getPath}. See #87.
 */
public interface FieldRef {

    String name();

    /** The value shape (boolean / ordered / text / reference / collection). */
    FieldKind kind();

    /** A display type label, e.g. "Integer", "Category", "List&lt;Category&gt;" (or null). */
    String typeLabel();

    /** An entity-valued field (its value is a {@link quiz.Quizable}). */
    boolean reference();

    /** A multi-valued field (its value is a collection/array). */
    boolean collection();

    /** A minor field — a rendering hint (compact / hidden-by-default), if the backing
     *  distinguishes them; false when it doesn't. */
    boolean minor();

    static FieldRef of(String name, FieldKind kind, String typeLabel,
                       boolean reference, boolean collection, boolean minor) {
        return new Impl(name, kind, typeLabel, reference, collection, minor);
    }

    record Impl(String name, FieldKind kind, String typeLabel,
                boolean reference, boolean collection, boolean minor) implements FieldRef {}
}
