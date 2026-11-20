package wikidata.explore.query.result;

import java.util.List;
import java.util.Set;

/** Bounded count and members for one candidate membership type. */
public record MembershipTypePreview(
        String qid,
        String label,
        boolean includeSubclasses,
        int boundedCount,
        boolean countCapped,
        List<MembershipTypeCandidate.Member> members,
        ClassFilters classFilters) {

    public MembershipTypePreview {
        qid = qid == null ? "" : qid;
        label = label == null ? "" : label;
        members = members == null ? List.of() : List.copyOf(members);
    }

    public MembershipTypePreview(String qid, String label, boolean includeSubclasses,
            int boundedCount, boolean countCapped,
            List<MembershipTypeCandidate.Member> members) {
        this(qid, label, includeSubclasses, boundedCount, countCapped, members, null);
    }

    /**
     * How many of the type's members the selected class would itself generate.
     * {@code count} is -1 when the class applies no filter beyond its type, so
     * there was nothing to count. {@code passing} is exact only when not capped.
     */
    public record ClassFilters(String className, List<String> filters, int classLimit,
            int count, boolean capped, Set<String> passing) {
        public ClassFilters {
            className = className == null ? "" : className;
            filters = filters == null ? List.of() : List.copyOf(filters);
            passing = passing == null ? Set.of() : Set.copyOf(passing);
        }

        public boolean checked() { return count >= 0; }

        /** Whether {@code qid} is known to pass, known not to, or unknown (null). */
        public Boolean passes(String qid) {
            if (!checked()) return Boolean.TRUE;
            return capped ? null : passing.contains(qid);
        }
    }
}
