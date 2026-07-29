package quiz.enrichment;

import java.util.List;

/** The accepted identity assignments from a resolve review — one per instance the user
 *  confirmed. Applied by writing an {@code IdentityLink} per entry. */
public record ResolveIdentitiesDecision(List<Resolved> resolved) {

    public ResolveIdentitiesDecision {
        resolved = resolved == null ? List.of() : List.copyOf(resolved);
    }

    public record Resolved(String targetId, String qid, String label) { }
}
