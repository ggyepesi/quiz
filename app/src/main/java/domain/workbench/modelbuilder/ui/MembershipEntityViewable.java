package domain.workbench.modelbuilder.ui;

import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.annotations.Hidden;
import wikidata.explore.query.result.MembershipTypeCandidate;

/** The common ObjectView row for membership examples and previewed members. */
final class MembershipEntityViewable extends ViewableAdapter {
    @Hidden private final MembershipTypeCandidate.Member member;
    @DisplayField private final String name;
    private final String qid;
    private final String description;

    MembershipEntityViewable(MembershipTypeCandidate.Member member) {
        this.member = member;
        this.name = member.label();
        this.qid = member.qid();
        this.description = member.description();
    }

    @Override public String getIdentifier() { return qid; }
    @Override public String getDisplayName() { return name; }
    String qid() { return qid; }
    MembershipTypeCandidate.Member member() { return member; }
}
