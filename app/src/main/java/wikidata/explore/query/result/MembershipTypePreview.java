package wikidata.explore.query.result;

import java.util.List;

/** Bounded count and representative members for one candidate membership type. */
public record MembershipTypePreview(
        String qid,
        boolean includeSubclasses,
        int boundedCount,
        boolean countCapped,
        List<MembershipTypeCandidate.Member> members) {

    public MembershipTypePreview {
        qid = qid == null ? "" : qid;
        members = members == null ? List.of() : List.copyOf(members);
    }
}
