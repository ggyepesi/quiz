package wikidata.explore.transform;

import canonical.CanonicalizationPlan;
import canonical.KeyComponent;
import canonical.MissingKeyPolicy;
import canonical.Reduction;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The provider adapter attaches results; it never partitions or resolves conflicts. */
class WikidataCanonicalizationTest {

    @Test void aCanonicalResultRetainsSourcesAndRemovesAnUnresolvedCarrierValue() {
        CanonicalizationPlan plan = new CanonicalizationPlan("Person",
                List.of(KeyComponent.field("modeledName")),
                MissingKeyPolicy.INCOMPLETE_GROUP,
                Map.of("note", Reduction.REQUIRE_AGREEMENT));
        WikidataDynamicObject first = person("Q2", "same", "right");
        WikidataDynamicObject second = person("Q1", "same", "left");

        WikidataCanonicalization.Result result = WikidataCanonicalization.apply(
                plan, List.of(first, second), null);

        assertEquals(1, result.carriers().size());
        assertSame(second, result.carriers().getFirst(),
                "carrier choice is stable and separate from partitioning");
        assertNull(second.get("note"),
                "a conflict with no selected value must not leak one input's value");
        assertEquals(List.of("wikidata:Q1", "wikidata:Q2"),
                result.reduction().instances().getFirst().sourceIdentities());
        assertSame(second, result.canonicalByCandidate().get(first));
        assertSame(second, result.canonicalByCandidate().get(second));
    }

    private static WikidataDynamicObject person(
            String qid, String modeledName, String note) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, modeledName);
        value.type("Person");
        value.put("modeledName", modeledName);
        value.put("note", note);
        return value;
    }
}
