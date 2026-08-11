package wikidata.explore.extract;

import wikidata.explore.extract.WikidataDynamicObject;

import org.junit.jupiter.api.Test;
import wikidata.api.WikidataApiClient;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.rule.RuleIncludedField;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void anExplicitMissingResponseMarksAReferenceForDeadStubPruning() {
        WikidataObjectRegistry registry = new WikidataObjectRegistry();
        RuleTreeExtractor extractor = new RuleTreeExtractor(null, registry);
        WikidataDynamicObject orphan = registry.getOrCreate("Q999999", "Q999999");

        extractor.applyLabels(List.of(orphan), Map.of(
                "Q999999", new WikidataApiClient.ApiEntity(
                        "Q999999", "", Map.of(), true)));

        assertEquals(true, orphan.isWikidataEntityMissing());
    }

    /**
     * A partly-resolved pass applies what came back and leaves the rest as QIDs.
     *
     * <p>The isolation itself lives in the API client (see BestEffortEntitiesTest) so
     * the batches keep fanning out; what this pins is the caller's half — one call,
     * and every label it did receive is applied rather than discarded with the batch
     * that failed.
     */
    @Test
    void appliesTheLabelsThatCameBackAndLeavesTheRestAsQids() {
        WikidataObjectRegistry registry = new WikidataObjectRegistry();
        AtomicInteger calls = new AtomicInteger();
        WikidataApiClient api = new WikidataApiClient("test") {
            @Override
            public PartialEntities getEntitiesBestEffort(
                    List<String> qids, List<String> claimPids, BatchLog queryLog) {
                calls.incrementAndGet();
                Map<String, ApiEntity> result = new LinkedHashMap<>();
                for (String qid : qids) {
                    // Stands in for the batch the endpoint refused: Q51..Q100 absent.
                    int n = Integer.parseInt(qid.substring(1));
                    if (n < 51 || n > 100) result.put(qid, entity(qid, "Label " + qid));
                }
                return new PartialEntities(result, 1);
            }
        };
        RuleTreeExtractor extractor = new RuleTreeExtractor(null, registry).api(api);
        for (int i = 1; i <= 101; i++) {
            registry.getOrCreate("Q" + i, "Q" + i);
        }

        extractor.resolveLabels(GenerationLog.NOOP);

        assertEquals(1, calls.get(),
                     "one call — batching here would serialise the client's fan-out");
        assertEquals("Label Q1", registry.get("Q1").getDisplayName());
        assertEquals("Q51", registry.get("Q51").getDisplayName());
        assertEquals("Label Q101", registry.get("Q101").getDisplayName());
    }
}
