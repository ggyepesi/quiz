package wikidata.explore.model;

/**
 * The semantic role a reified field plays, so reification-quality checks can tell
 * the subject's own identity apart from references to other entities.
 *
 * <p>Used by the self-referential phantom detection (#99): a work's degenerate
 * self-copy is only a duplicate when a real record references that work through a
 * {@link #REFERENCE} role (e.g. {@code forWork}); a record that merely names the
 * subject through the {@link #IDENTITY} role (e.g. {@code nominee}) is a different
 * nomination, not a denormalization.
 */
public enum RoleKind {

    /** The subject's own role — e.g. the nominee IS the statement subject. */
    IDENTITY,

    /** A reference to another entity — e.g. forWork, edition. The default. */
    REFERENCE,

    /** The reified statement's own value. */
    VALUE
}
