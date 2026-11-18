package wikidata.explore.query.result;

import java.util.List;

/** Bounded count and members for one candidate membership type. */
public record MembershipTypePreview(
        String qid,
        String label,
        boolean includeSubclasses,
        int boundedCount,
        boolean countCapped,
        List<MembershipTypeCandidate.Member> members) {

    public MembershipTypePreview {
        qid = qid == null ? "" : qid;
        label = label == null ? "" : label;
        members = members == null ? List.of() : List.copyOf(members);
    }
}
