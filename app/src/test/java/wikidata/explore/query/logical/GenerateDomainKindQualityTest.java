package wikidata.explore.query.logical;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

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
}
