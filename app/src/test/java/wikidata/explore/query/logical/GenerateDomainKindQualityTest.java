package wikidata.explore.query.logical;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import wikidata.explore.generation.GenerationPipeline;
import wikidata.explore.generation.PopulationSourceExecution;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerateDomainKindQualityTest {

    /** The old summary called this a served-type count and promised that it matched
     * reload, although it omits statement records and extra class memberships that Save
     * domain writes. The label must state the narrower fact it actually computes. */
    @Test void generationSummaryNamesExactlyWhatItCounts() {
        WikidataDynamicObject person = new WikidataDynamicObject("Q1", "One");
        person.type("Person");
        person.assignClass("Politician");
        WikidataDynamicObject duplicate = new WikidataDynamicObject("Q1", "One again");
        duplicate.type("Person");
        WikidataDynamicObject position = new WikidataDynamicObject("Q2", "Position");
        position.type("Position");
        WikidataDynamicObject statement = new WikidataDynamicObject("Q1$P39$1", "Holding");
        statement.type("OfficeHolding");

        assertEquals("""
                Generated QID entities by primary class (statement records and additional class memberships are not included):
                Person\t1
                Position\t1
                TOTAL\t2 QID entities across 2 primary class(es); 1 root class query(ies)
                This is not the saved snapshot census. Save domain writes the complete referenced object graph and reports its class-membership counts in the domain's .counts.tsv file.""",
                GenerateDomainQuery.generatedQidSummary(
                        List.of(person, duplicate, position, statement), 1));
    }

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
