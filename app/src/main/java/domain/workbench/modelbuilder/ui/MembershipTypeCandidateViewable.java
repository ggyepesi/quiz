package domain.workbench.modelbuilder.ui;

import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.annotations.Hidden;
import wikidata.explore.query.result.MembershipTypeCandidate;

import java.util.List;

/** ObjectView projection shared by subtype and example-driven membership discovery. */
final class MembershipTypeCandidateViewable extends ViewableAdapter {
    @Hidden private final MembershipTypeCandidate candidate;
    @DisplayField private final String name;
    private final String qid;
    private final String evidence;
    private final String population;
    private final List<String> exampleLabels;
    private final List<String> exampleQids;

    MembershipTypeCandidateViewable(MembershipTypeCandidate candidate) {
        this.candidate = candidate;
        this.name = candidate.label();
        this.qid = candidate.qid();
        this.evidence = evidence(candidate);
        this.population = candidate.exactPopulation() < 0
                ? "Preview to count members"
                : candidate.exactPopulation() + " direct instance(s)";
        this.exampleLabels = candidate.examples().stream()
                .map(MembershipTypeCandidate.Member::label).toList();
        this.exampleQids = candidate.examples().stream()
                .map(MembershipTypeCandidate.Member::qid)
                .filter(wikidata.WikidataIds::isQid).toList();
    }

    MembershipTypeCandidate candidate() { return candidate; }

    @Override public String getIdentifier() { return candidate.qid(); }
    @Override public String getDisplayName() { return name; }

    private static String evidence(MembershipTypeCandidate candidate) {
        return switch (candidate.origin()) {
            case BROAD_TYPE -> "Selected broad type";
            case DIRECT_SUBTYPE -> "Direct subclass of " + candidate.anchorQid();
            case COMMON_TYPE -> candidate.coveredExamples() + " of "
                    + candidate.totalExamples() + " example(s); direct P31 type of "
                    + candidate.directExamples();
        };
    }
}
