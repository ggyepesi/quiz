package quiz;

/**
 * Marks a Viewable that is a VALUE, not an entity: it has no identity, is never shared
 * or browsed on its own, and is serialized INLINE within its parent — not stored as a
 * pooled, qid-keyed entity and referenced by a {@code Ref}. It may still have a display
 * name, but as a LABEL, not an identifier (so no id needs to be invented for it — the
 * error-prone thing that caused collisions/blank-drops).
 *
 * <p>Examples: a Nobel {@code LaureatesWithMotivation} grouping, a parsed
 * {@code Motivation}. A shared, browsable thing (a {@code Person}, a {@code NobelPrize})
 * is an ENTITY and does NOT implement this.
 */
public interface ValueObject {
}
