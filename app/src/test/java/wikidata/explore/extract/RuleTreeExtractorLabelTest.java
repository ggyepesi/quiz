package wikidata.explore.extract;

import wikidata.explore.extract.WikidataDynamicObject;

import org.junit.jupiter.api.Test;
import wikidata.api.WikidataApiClient;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.rule.RuleIncludedField;

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
    void appliesOutgoingClaimValuesAndFillsBlankMemberLabel() {
        WikidataObjectRegistry registry = new WikidataObjectRegistry();
        RuleTreeExtractor extractor = new RuleTreeExtractor(null, registry);

        WikidataDynamicObject m1 = registry.getOrCreate("Q11", "Q11");   // unlabeled
        WikidataDynamicObject m2 = registry.getOrCreate("Q22", "Casablanca");

        RuleIncludedField type = new RuleIncludedField();
        type.fieldName("type");
        type.propertyPid("P31");
        type.direction(RuleDirection.ROOT_TO_ITEM);
        type.kind(RuleIncludedField.FieldKind.ENTITY);
        type.collection(true);

        Map<String, WikidataApiClient.ApiEntity> details = Map.of(
                "Q11", new WikidataApiClient.ApiEntity(
                        "Q11", "The Godfather", Map.of("P31", List.of("Q11424", "Q5"))),
                "Q22", new WikidataApiClient.ApiEntity(
                        "Q22", "ignored", Map.of("P31", List.of("Q11424"))));

        int filled = extractor.applyEntityClaims(List.of(m1, m2), List.of(type), details);

        assertEquals(2, filled);
        // type filled as canonical refs, in order.
        List<?> t1 = (List<?>) m1.get("type");
        assertEquals(List.of("Q11424", "Q5"),
                t1.stream().map(o -> ((WikidataDynamicObject) o).qid()).toList());
        // blank member label filled; already-named member left untouched.
        assertEquals("The Godfather", m1.getDisplayName());
        assertEquals("Casablanca", m2.getDisplayName());
        // shared type ref (Q11424) is canonical across members.
        assertEquals(registry.get("Q11424"), m2.get("type"));
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
