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
         * The confident pick: the FIRST exact-label match, or null when none matches.
         *
         * <p>Candidates come back in Wikidata relevance order, so the top exact hit is the
         * canonical entity ("India" → Q668). Homonyms sharing the label no longer demote it
         * to ambiguous — ranking decides.</p>
         *
         * <p>This is the classification the review surface groups by, so it belongs to the
         * data rather than to whichever panel renders it.</p>
         */
        public IdentityMatch exactMatch() {
            for (IdentityMatch match : candidates) {
                if (match.label() != null && name != null
                        && match.label().equalsIgnoreCase(name)) {
                    return match;
                }
            }
            return null;
        }
    }

    /** A Wikidata entity candidate for an instance. */
    public record IdentityMatch(String qid, String label, String description) { }
}
