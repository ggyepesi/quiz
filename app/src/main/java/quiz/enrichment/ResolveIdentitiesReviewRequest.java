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
            String type,
            String targetId,
            String name,
            String currentQid,
            List<IdentityMatch> candidates) {

        public InstanceIdentity {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        /**
         * The confident pick: the TOP-RANKED candidate, and only when its label matches
         * this instance's name exactly. Null otherwise — which routes the instance to the
         * ambiguous group, where the same top candidate is offered pre-selected.
         *
         * <p>Candidates come back in relevance order, so an exact label at rank 0 is the
         * canonical entity ("India" → Q668, undisturbed by the homonym given name that
         * shares its label). Accepting an exact label at ANY rank instead let a worse
         * candidate overrule a better one: searching "Franklin D. Roosevelt" ranks Q8007
         * first but labels him "Franklin <b>Delano</b> Roosevelt", so the exactly-labelled
         * Paris Métro station at rank 1 won — and confident results are the ones a bulk
         * Stage writes without further review.</p>
         *
         * <p>This is the classification the review surface groups by, so it belongs to the
         * data rather than to whichever panel renders it.</p>
         */
        public IdentityMatch exactMatch() {
            if (candidates.isEmpty()) {
                return null;
            }
            IdentityMatch top = candidates.get(0);
            return top.label() != null && name != null
                    && top.label().equalsIgnoreCase(name) ? top : null;
        }
    }

    /** A Wikidata entity candidate for an instance. */
    public record IdentityMatch(String qid, String label, String description) { }
}
