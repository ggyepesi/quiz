package domain.workbench.modelbuilder.ui;

import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.annotations.Hidden;
import domain.analysis.RelationRouteDiscovery;

import java.util.List;

/** ObjectView row for one route discovered from explicit examples. */
final class RelationRouteCandidateViewable extends ViewableAdapter {
    @Hidden
    private final RelationRouteDiscovery.Candidate candidate;
    @DisplayField
    private final String name;
    private final List<String> steps;
    private final String coverage;
    private final String additionalResults;
    private final List<String> sourceExamples;
    private final List<String> targetExamples;

    RelationRouteCandidateViewable(RelationRouteDiscovery.Candidate candidate) {
        this.candidate = candidate;
        this.name = candidate.route().label();
        this.steps = candidate.stepLabels();
        this.coverage = candidate.matchedExamples() + " / " + candidate.totalExamples();
        this.additionalResults = candidate.additionalResults()
                + " other source–target value(s) reached within this search";
        this.sourceExamples = candidate.witnesses().stream()
                .map(RelationRouteDiscovery.Witness::sourceQid).distinct().toList();
        this.targetExamples = candidate.witnesses().stream()
                .map(RelationRouteDiscovery.Witness::targetQid).distinct().toList();
    }

    RelationRouteDiscovery.Candidate candidate() { return candidate; }

    @Override public String getIdentifier() { return candidate.route().id(); }
    @Override public String getDisplayName() { return name; }
}
