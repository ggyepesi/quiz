package wikidata.explore.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import wikidata.api.WikidataFactStore;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FactDemandBinderTest {

    @Test
    void bindsAClassPlanToExactQidsAndDeduplicatesOverlappingConsumers()
            throws Exception {
        WikidataFactStore facts = new WikidataFactStore();
        List<FactDemand> demands = List.of(
                FactDemand.of("kind classifier", "Nominee", List.of("P31", "P569"), ""),
                FactDemand.of("disambiguation", "Nominee", List.of("P31"), ""),
                FactDemand.metadata("names", "Nominee",
                        List.of(FactDemand.EntityMetadata.LABEL,
                                FactDemand.EntityMetadata.ALIASES), ""));

        FactDemandBinder.Binding bound = FactDemandBinder.bind(
                demands, List.of("Q1", "Q2", "not-a-qid", "Q1"), facts,
                "semantic preflight");

        assertEquals(2, bound.entities());
        assertEquals(4, bound.claimPairs(), "2 QIDs × the unique P31/P569 closure");
        assertEquals(4, bound.metadataPairs(), "2 QIDs × label/aliases");
        assertEquals(3, bound.consumers());

        facts.accept(new ObjectMapper().readTree("""
                {"entities":{
                  "Q1":{"id":"Q1","claims":{"P31":[],"P569":[]}},
                  "Q2":{"id":"Q2","claims":{"P31":[],"P569":[]}}
                }}
                """), true, List.of("P31", "P569"));
        facts.recordDemand("kind classifier", List.of("Q1", "Q2"), List.of("P31"));

        assertEquals(0, facts.lateDemandPairs(),
                "a consumer may run later without becoming an unplanned late demand");
        assertEquals(2, facts.preplannedDemandPairs());
    }
}
