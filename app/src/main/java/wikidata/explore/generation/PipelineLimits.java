package wikidata.explore.generation;

/**
 * How much a run may read — the only thing that makes a bounded run bounded.
 *
 * <p>Stated as values rather than as phases left out. That is the design's central
 * claim about previews: "a preview may contain fewer instances, but every included
 * instance is produced with the same semantics as full generation". Every time a bounded
 * flow has instead dropped a phase, the result was not a smaller answer but a different
 * one — a sampled Nominee that never became a Person, an aggregate group missing most of
 * its members.
 *
 * @param members how many members of the scope to read, or {@link #UNBOUNDED} for as
 *                many as the model's own configuration allows
 * @param depth   how many levels of child-object reference edges to follow, or
 *                {@link #UNBOUNDED} to use each class's own configured depth
 */
public record PipelineLimits(int members, int depth) {

    /** Not "no limit": the model's own limits still apply. This adds none. */
    public static final int UNBOUNDED = 0;

    public PipelineLimits {
        members = Math.max(UNBOUNDED, members);
        depth = Math.max(UNBOUNDED, depth);
    }

    /** As much as the model itself allows — what a full generation reads. */
    public static PipelineLimits asConfigured() {
        return new PipelineLimits(UNBOUNDED, UNBOUNDED);
    }

    /** The first {@code members} of the scope, at the model's configured depth. */
    public static PipelineLimits members(int members) {
        return new PipelineLimits(members, UNBOUNDED);
    }

    public boolean bounded() {
        return members != UNBOUNDED || depth != UNBOUNDED;
    }

    @Override public String toString() {
        if (!bounded()) return "as configured";
        StringBuilder said = new StringBuilder();
        if (members != UNBOUNDED) said.append(members).append(" member(s)");
        if (depth != UNBOUNDED) {
            said.append(said.isEmpty() ? "" : ", ").append("depth ").append(depth);
        }
        return said.toString();
    }
}
