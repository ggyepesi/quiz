package wikidata.explore.query.result;

import java.util.List;

/** One Wikidata class proposed as a Source class's membership type. */
public record MembershipTypeCandidate(
        String qid,
        String label,
        Origin origin,
        String anchorQid,
        int coveredExamples,
        int totalExamples,
        int directExamples,
        long exactPopulation,
        List<Member> examples) {

    public enum Origin { BROAD_TYPE, DIRECT_SUBTYPE, COMMON_TYPE }

    public record Member(String qid, String label, String description) {
        public Member(String qid, String label) { this(qid, label, ""); }
        public Member {
            qid = qid == null ? "" : qid;
            label = label == null || label.isBlank() ? qid : label;
            description = description == null ? "" : description;
        }
    }

    public MembershipTypeCandidate {
        qid = qid == null ? "" : qid;
        label = label == null || label.isBlank() ? qid : label;
        origin = origin == null ? Origin.COMMON_TYPE : origin;
        anchorQid = anchorQid == null ? "" : anchorQid;
        examples = examples == null ? List.of() : List.copyOf(examples);
    }
}
