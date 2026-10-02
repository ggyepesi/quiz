package wikidata.explore.query.logical;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import wikidata.explore.generation.GenerationPipeline;
import wikidata.explore.generation.PopulationSourceExecution;
import wikidata.explore.model.GeneratedProjectModel;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerateDomainKindQualityTest {

    @Test void reconcilesUnavailableKindEvidenceByIdentityNotPopulationCounts() {
        Set<String> unresolved = GenerateDomainQuery.unresolvedKindEvidenceQids(
                List.of("Q1", "Q2", "Q3"),
                Set.of("Q2", "Q3", "Q9"));

        assertEquals(Set.of("Q2", "Q3"), unresolved,
                "Q1 recovered; unrelated Q9 must not offset an unavailable identity");
    }

    @Test void allUnavailableEvidenceCanBeRepaired() {
        assertEquals(Set.of(), GenerateDomainQuery.unresolvedKindEvidenceQids(
                List.of("Q1", "Q2"), Set.of("Q9")));
    }

    @Test void supplementalQidsAreUnionedWithRatherThanReplacingConfiguredPopulation() {
        GeneratedProjectModel model = GeneratedProjectModel.constellationDemo();
        var configured = new PopulationSourceExecution.Resolution(
                PopulationSourceExecution.Resolution.Kind.LOCAL_SOURCE,
                List.of(), List.of(), null, "");

        GenerationPipeline pipeline = new GenerationPipeline();
        var plans = GenerateDomainQuery.populationPlans(
                pipeline.plan(model), pipeline.plan(model),
                configured, List.of("Q42", "Q1"));

        assertEquals(2, plans.size());
        assertEquals(model.rootClass().membership().relationPid(),
                plans.getFirst().propertyPid(),
                "the configured population remains its own query");
        assertEquals(Set.of("Q42", "Q1"), plans.getLast().includedQids(),
                "the applied graph identities use a second exact-QID query");
        assertEquals("", plans.getLast().propertyPid());
    }
}
