package wikidata.explore.extract;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.rule.RuleIncludedField;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Slice-1 step 4 — materializing the target field from the membership-edge map:
 * each member's insertion-ordered set of target qids resolves to canonical registry
 * refs, deduped, in order. No network.
 */
class RuleTreeExtractorMembershipTargetTest {

    private static RuleIncludedField targetField() {
        RuleIncludedField f = new RuleIncludedField();
        f.fieldName("target");
        f.propertyPid("P1411");
        f.direction(RuleDirection.ROOT_TO_ITEM);
        f.kind(RuleIncludedField.FieldKind.ENTITY);
        f.collection(true);
        return f;
    }

    @Test
    void fillsTargetsFromEdgeMapWithCanonicalRefsInOrder() {
        WikidataObjectRegistry registry = new WikidataObjectRegistry();
        RuleTreeExtractor extractor = new RuleTreeExtractor(null, registry);

        WikidataDynamicObject m1 = registry.getOrCreate("Q11", "First nominee");
        WikidataDynamicObject m2 = registry.getOrCreate("Q22", "Second nominee");

        Map<String, LinkedHashSet<String>> edges = new LinkedHashMap<>();
        edges.put("Q11", new LinkedHashSet<>(List.of("Q102427", "Q281939")));
        edges.put("Q22", new LinkedHashSet<>(List.of("Q281939")));

        extractor.materializeMembershipTargets(
                List.of(m1, m2), List.of(targetField()), edges);

        // m1 → both categories, in insertion order, as entity refs.
        List<?> t1 = (List<?>) m1.get("target");
        assertEquals(List.of("Q102427", "Q281939"),
                t1.stream().map(o -> ((WikidataDynamicObject) o).qid()).toList());

        // m2 → a single category (kept as one value, not a list — merge semantics).
        Object t2 = m2.get("target");
        assertEquals("Q281939", ((WikidataDynamicObject) t2).qid());

        // The shared category (Q281939) is the SAME canonical registry object on both.
        WikidataDynamicObject sharedOnM1 = (WikidataDynamicObject) t1.get(1);
        assertSame(sharedOnM1, t2, "target refs are canonicalized by the registry");
        assertSame(registry.get("Q281939"), t2);
    }

    @Test
    void memberWithoutEdgesGetsNoTargetField() {
        WikidataObjectRegistry registry = new WikidataObjectRegistry();
        RuleTreeExtractor extractor = new RuleTreeExtractor(null, registry);
        WikidataDynamicObject lonely = registry.getOrCreate("Q99", "No categories");

        extractor.materializeMembershipTargets(
                List.of(lonely), List.of(targetField()), new LinkedHashMap<>());

        assertEquals(null, lonely.get("target"));
    }
}
