package wikidata.explore.extract;

/**
 * Where a reified atom's field value came from. Recorded by the reify step as
 * sidecar provenance (see {@link WikidataDynamicObject#recordOrigin}) so
 * downstream passes can reason about degenerate / self-referential atoms
 * generically — e.g. "every role field fell back to the subject" — without any
 * domain-specific field checks.
 *
 * <p>This is metadata only: it is never a rendered field, never in
 * {@code dynamicFieldValues()}, and never serialized.
 */
public enum FieldOrigin {

    /** The value came from the statement's own qualifier (a real value). */
    QUALIFIER,

    /** The qualifier was absent; the value fell back to the statement subject. */
    SUBJECT_FALLBACK,

    /** The qualifier was absent and no fallback applied — the field was unset. */
    MISSING
}
