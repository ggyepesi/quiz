package quiz.enrichment;

import process.ProcessInputRequest;

import java.util.List;

/**
 * One review over identity candidates for a scope of instances: each instance with its
 * current identity (if any) and the Wikidata entities a label search found. The UI returns
 * the accepted ⟨instance → qid⟩ assignments. Identity resolution is field-independent — it
 * is the foundational step that turns manual instances into addressable entities.
 */
public record ResolveIdentitiesReviewRequest(
        String title,
        String prompt,
        List<InstanceIdentity> instances)
        implements ProcessInputRequest<ResolveIdentitiesDecision> {

    public ResolveIdentitiesReviewRequest {
        instances = instances == null ? List.of() : List.copyOf(instances);
    }

    @Override public Class<ResolveIdentitiesDecision> responseType() {
        return ResolveIdentitiesDecision.class;
    }

    /** One instance to identify: its domain id + name, current qid (blank if none), and the
     *  ranked candidate matches from the label search. */
    public record InstanceIdentity(
            String targetId,
            String name,
            String currentQid,
            List<IdentityMatch> candidates) {

        public InstanceIdentity {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    /** A Wikidata entity candidate for an instance. */
    public record IdentityMatch(String qid, String label, String description) { }
}
