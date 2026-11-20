package wikidata.explore.workbench;

import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import wikidata.explore.query.result.MembershipTypeCandidate;
import wikidata.explore.query.result.MembershipTypePreview;

/**
 * A previewed member of a candidate membership type, with whether the selected class's
 * own population filters keep it. Examples have no such answer, so they keep
 * {@link MembershipEntityViewable} rather than carrying an always-empty column.
 */
final class PreviewMemberViewable extends ViewableAdapter {
    static final String KEPT = "kept";
    static final String REMOVED = "removed by class filters";
    static final String UNKNOWN = "not checked (count capped)";

    @DisplayField private final String name;
    private final String qid;
    private final String description;
    private final String inClass;

    PreviewMemberViewable(MembershipTypeCandidate.Member member,
            MembershipTypePreview.ClassFilters filters) {
        this.name = member.label();
        this.qid = member.qid();
        this.description = member.description();
        Boolean passes = filters == null ? null : filters.passes(member.qid());
        this.inClass = filters == null ? "" : passes == null ? UNKNOWN
                : passes ? KEPT : REMOVED;
    }

    @Override public String getIdentifier() { return qid; }
    @Override public String getDisplayName() { return name; }
    String inClass() { return inClass; }
}
