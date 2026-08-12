package quiz.enrichment;

import objectview.Viewable;

/**
 * Turns a QID into the instance a reference field can point at.
 *
 * <p>Without this, an entity-valued property was fetched correctly and then thrown away:
 * the claim yields a QID, a reference field requires a {@link Viewable}, and nothing
 * bridged the two — so every candidate was marked schema-incompatible and a field like
 * {@code Movies.locations} reported nothing found however it was configured.
 *
 * <p>A seam rather than a direct dependency, because resolving means consulting the
 * pool that owns instance identity, and a provider must not reach into it. Whoever holds
 * the domain supplies this.
 */
@FunctionalInterface
public interface ReferenceResolver {

    /**
     * The instance for {@code qid}, created with {@code label} when the pool has none.
     *
     * @param qid        the target entity's QID
     * @param label      its name, already resolved — never the QID itself when a real
     *                   label was available, or the created instance starts life as one
     *                   of the unnamed references curation exists to fix
     * @param targetType the class the field expects, so a created instance is stamped
     *                   as one rather than joining the pool untyped
     * @return the resolved instance, or null when this resolver cannot supply one
     */
    Resolved resolve(String qid, String label, String targetType);

    /** The instance, and whether it had to be created — the difference between "link to
     *  New York City" and "add New York City", which review must state plainly. */
    record Resolved(Viewable value, boolean created) { }
}
