package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.EntityBound;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both ends of a discovery join can now be pinned.
 *
 * <p>R16 says a join anchored on one side spans every subject of the property in
 * Wikidata, soft-times-out, and returns a different partial row set each run. The object
 * side was always pinned here; the subject side could not be, because the model had no
 * way to say which entities may be subjects. That was not a decision — it was a missing
 * capability, and the object-only guard encoded it as though it were the rule.
 */
class SubjectBoundTest {

    @Test void anUnboundedSubjectAddsNothingToTheQuery() {
        String query = PopulationSubjectLoader.buildQuery(
                "P39", Set.of("Q6412254"), EntityBound.unbounded(), 0);

        assertFalse(query.contains("?subjectKind"), query);
        assertFalse(query.contains("VALUES ?subject {"), query);
        assertTrue(query.contains("?subject wdt:P39 ?value"), query);
    }

    @Test void anExplicitSubjectSetIsPinnedAsValues() {
        String query = PopulationSubjectLoader.buildQuery(
                "P39", Set.of("Q6412254"),
                EntityBound.explicit(List.of("Q1001", "Q1002")), 0);

        assertTrue(query.contains("VALUES ?subject { wd:Q1001 wd:Q1002 }"), query);
        assertTrue(query.contains("VALUES ?value { wd:Q6412254 }"), query);
    }

    @Test void aSubjectKindIsPinnedAsAPatternNotAPreExpandedList() {
        String query = PopulationSubjectLoader.buildQuery(
                "P39", Set.of("Q6412254"), EntityBound.instancesOf("Q5"), 0);

        assertTrue(query.contains("?subject wdt:P31 ?subjectKind"), query);
        assertTrue(query.contains("VALUES ?subjectKind { wd:Q5 }"), query);
        assertFalse(query.contains("wdt:P279*"),
                "no closure unless the bound asks for it");
    }

    @Test void descendantsBecomeP279ClosureOnTheTarget() {
        String query = PopulationSubjectLoader.buildQuery(
                "P39", Set.of("Q6412254"),
                EntityBound.relation("P31", List.of("Q5"), true), 0);

        assertTrue(query.contains("?subject wdt:P31/wdt:P279* ?subjectKind"), query);
    }

    /**
     * The guard demanded the OBJECT end specifically. That was never the requirement —
     * it was the only end that COULD be bounded, so the restriction looked like a rule.
     * Either end pins the join.
     */
    @Test void aBoundedSubjectAloneIsEnoughToDiscoverSafely() {
        String query = PopulationSubjectLoader.buildQuery(
                "P39", Set.of(), EntityBound.explicit(List.of("Q1001")), 0);

        assertTrue(query.contains("VALUES ?subject { wd:Q1001 }"), query);
        assertFalse(query.contains("VALUES ?value"),
                "nothing bounds the objects, and nothing pretends to");
    }

    @Test void neitherEndBoundedStillRefusesToScanWikidata() {
        List<?> discovered = new PopulationSubjectLoader().discover(
                List.of(), "P39", Set.of(), EntityBound.unbounded(),
                "__Holding", "", null, null, 0);

        assertEquals(List.of(), discovered);
    }
}
