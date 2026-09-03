package canonical;

/**
 * One thing a datasource produced, before anything has decided what it identifies.
 *
 * <p>The map phase emits these; the reduce phase turns them into instances. A candidate
 * knows its class, its field values and the identities its production supplies — and
 * nothing about what will become of it, which is the boundary this whole design exists to
 * draw. Several candidates may denote one instance; that is what the key decides, not the
 * candidate.
 */
public interface Candidate {

    /** The class this candidate is a candidate FOR. */
    String className();

    /** A field's typed value, or null. Provenance rides inside the value: reduction
     *  never decomposes a value, so it cannot separate one from its evidence. */
    Object value(String fieldPath);

    /**
     * An identity production supplies rather than the candidate's values — the source's
     * own id, the owner and site that made a part, or this candidate's occurrence at its
     * source.
     *
     * @return the identity, or "" when this candidate has none of that kind
     */
    String structuralIdentity(KeyComponent.Kind kind);
}
