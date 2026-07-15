package wikidata.explore.extract;

import org.junit.jupiter.api.Test;
import wikidata.api.WikidataApiClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Slice 3 — applying resolved wbgetentities labels onto the QID-placeholder refs the
 * captures produced, without touching already-named objects. No network.
 */
class RuleTreeExtractorLabelTest {

    private static WikidataApiClient.ApiEntity entity(String qid, String label) {
        return new WikidataApiClient.ApiEntity(qid, label, Map.of());
    }

    @Test
    void namesPlaceholdersAndLeavesAlreadyNamedRefsUntouched() {
        WikidataObjectRegistry registry = new WikidataObjectRegistry();
        RuleTreeExtractor extractor = new RuleTreeExtractor(null, registry);

        // placeholder: created with the qid as its name (QID-only capture)
        WikidataDynamicObject category = registry.getOrCreate("Q102427", "Q102427");
        // already named (e.g. a backbone member the SERVICE labelled)
        WikidataDynamicObject member = registry.getOrCreate("Q11", "Casablanca");

        Map<String, WikidataApiClient.ApiEntity> details = Map.of(
                "Q102427", entity("Q102427", "Academy Award for Best Picture"),
                "Q11", entity("Q11", "Should NOT overwrite"));

        int filled = extractor.applyLabels(List.of(category, member), details);

        assertEquals(1, filled, "only the placeholder is filled");
        assertEquals("Academy Award for Best Picture", category.getDisplayName());
        assertEquals("Casablanca", member.getDisplayName(), "named ref untouched");
    }

    @Test
    void aMissingResolutionLeavesTheQidPlaceholder() {
        WikidataObjectRegistry registry = new WikidataObjectRegistry();
        RuleTreeExtractor extractor = new RuleTreeExtractor(null, registry);
        WikidataDynamicObject orphan = registry.getOrCreate("Q999999", "Q999999");

        int filled = extractor.applyLabels(List.of(orphan), Map.of());

        assertEquals(0, filled);
        assertEquals("Q999999", orphan.getDisplayName());
    }
}
