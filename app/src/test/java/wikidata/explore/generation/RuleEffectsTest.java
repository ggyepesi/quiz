package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldExpectation;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;
import wikidata.explore.transform.FieldExpectations;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A generation's result already states its configuration implicitly — the instances are
 * typed by the generated class and the card shows the fields that class declares, so the
 * reader reads the config through the data. A Remap had no equivalent: it reported the
 * size of the pool, which barely moves, and offered every object in it as one flat list.
 *
 * <p>These are the same reading generalized. One bucket per configured rule, holding the
 * instances that rule accounts for, so "what did the Remap do" is answered by the rules
 * themselves rather than by a change report written alongside them.
 */
class RuleEffectsTest {

    private static WikidataDynamicObject nomination(String id, Object ceremony) {
        WikidataDynamicObject o = new WikidataDynamicObject(id, id);
        o.type("Nomination");
        if (ceremony != null) o.put("ceremony", ceremony);
        return o;
    }

    private static GeneratedProjectModel model(FieldExpectation level) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("oscars");
        project.rootClass(new GeneratedClassModel("Root"));
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new StatementClassSource("Root", "P1411"));
        GeneratedFieldModel ceremony =
                nomination.addField("ceremony", FieldType.ENTITY, FieldCardinality.SINGLE);
        ceremony.mapping().qualifierPid("P805");
        ceremony.expectation(level);
        project.addClass(nomination);
        return project;
    }

    private static List<WikidataDynamicObject> pool() {
        WikidataDynamicObject edition = new WikidataDynamicObject("Q66707607", "95th");
        edition.type("Edition");
        return new ArrayList<>(List.of(
                nomination("n1", edition), nomination("n2", edition),
                nomination("n3", null), nomination("n4", null)));
    }

    @Test void aRuleBucketHoldsTheInstancesItAccountsForNotACount() {
        List<RuleEffects.Effect> effects =
                RuleEffects.of(model(FieldExpectation.EXPECTED), pool());

        assertEquals(1, effects.size());
        RuleEffects.Effect effect = effects.get(0);
        assertEquals(2, effect.size(), "the two with no ceremony");
        assertEquals(List.of("n3", "n4"), effect.instances().stream()
                        .map(objectview.Viewable::getIdentifier).toList(),
                "and you can open the bucket and act on them, which a number never was");
    }

    @Test void expectedNamesInstancesWithoutClaimingItChangedThem() {
        // The distinction an expectation was designed around. A report that called both
        // levels "affected" would say 2 things happened when nothing did.
        RuleEffects.Effect effect =
                RuleEffects.of(model(FieldExpectation.EXPECTED), pool()).get(0);

        assertEquals(RuleEffects.Kind.FLAGGED, effect.kind());
        assertTrue(effect.detail().contains("will be kept"), effect.detail());
    }

    @Test void requiredIsReportedAsAChangeBecauseItDeletes() {
        RuleEffects.Effect effect =
                RuleEffects.of(model(FieldExpectation.REQUIRED), pool()).get(0);

        assertEquals(RuleEffects.Kind.CHANGED, effect.kind());
        assertTrue(effect.detail().contains("dropped"), effect.detail());
    }

    @Test void evaluatingTheRulesChangesNothing() {
        // What lets the plan ask the same question as the result: a REQUIRED rule
        // reports what it would delete without deleting it.
        List<WikidataDynamicObject> pool = pool();

        RuleEffects.of(model(FieldExpectation.REQUIRED), pool);

        assertEquals(4, pool.size(), "a plan must not apply the run it is describing");
    }

    @Test void findingNothingIsNotAClaimAboutTheWholeRun() {
        // "every declared rule holds" read as a clean bill of health for a remap that
        // also classified kinds, restricted values, built inverts, projected fields and
        // composed owned parts — none of which report here. The wording belongs with the
        // code that knows its own coverage, so a caller cannot overclaim on its behalf.
        assertEquals(RuleEffects.NOTHING_REPORTED, RuleEffects.describe(List.of()));
        assertEquals(RuleEffects.NOTHING_REPORTED, RuleEffects.describe(null));
        assertTrue(!RuleEffects.NOTHING_REPORTED.toLowerCase().contains("every"),
                "it must not read as a statement about rules it never looked at: "
                        + RuleEffects.NOTHING_REPORTED);
    }

    @Test void aRuleThatReportsIsDescribedByWhatItFound() {
        assertEquals(RuleEffects.summary(
                        RuleEffects.of(model(FieldExpectation.EXPECTED), pool())),
                RuleEffects.describe(
                        RuleEffects.of(model(FieldExpectation.EXPECTED), pool())));
    }

    // ---- the self-referential phantom rule (#99) --------------------------------

    private static WikidataDynamicObject atom(String id) {
        WikidataDynamicObject o = new WikidataDynamicObject(id, id);
        o.type("Nomination");
        return o;
    }

    private static List<wikidata.explore.transform.TransformEngine.SelfRefFinding>
            findings() {
        return List.of(
                new wikidata.explore.transform.TransformEngine.SelfRefFinding(
                        wikidata.explore.transform.TransformEngine.SelfRefDecision.DROPPED,
                        atom("whale"), atom("hongChau"), List.of("category"),
                        "a real record references this subject through a REFERENCE role"),
                new wikidata.explore.transform.TransformEngine.SelfRefFinding(
                        wikidata.explore.transform.TransformEngine.SelfRefDecision.KEPT,
                        atom("dianeWarren"), null, List.of("category"),
                        "fully self-referential but no witness on the same slot"));
    }

    @Test void droppingAndDecidingNotToAreDifferentAnswersAndGetDifferentBuckets() {
        // A reader checking this rule wants both: the copies it removed, and the
        // records that looked identical but were kept because nothing witnessed them.
        // A film IS its own Best Picture nominee; only a witness makes it a duplicate.
        List<RuleEffects.Effect> effects = RuleEffects.fromSelfReference(
                findings(), RuleEffects.Moment.RESULT);

        assertEquals(2, effects.size());
        assertEquals(RuleEffects.Kind.CHANGED, effects.get(0).kind());
        assertEquals(RuleEffects.Kind.FLAGGED, effects.get(1).kind());
        assertEquals(List.of("whale"), effects.get(0).instances().stream()
                .map(objectview.Viewable::getIdentifier).toList());
        assertEquals(List.of("dianeWarren"), effects.get(1).instances().stream()
                .map(objectview.Viewable::getIdentifier).toList());
    }

    @Test void aKeptSelfReferenceIsNeverDescribedAsSomethingTheRunDid() {
        RuleEffects.Effect kept = RuleEffects.fromSelfReference(
                findings(), RuleEffects.Moment.RESULT).get(1);

        assertEquals(RuleEffects.Kind.FLAGGED, kept.kind());
        assertTrue(kept.detail().contains("kept"), kept.detail());
        assertTrue(!kept.detail().contains("dropped"), kept.detail());
    }

    @Test void aRunThatReifiedNothingReportsNoSelfReferenceBuckets() {
        // A loaded snapshot cannot replay the reify, so its run records no decisions —
        // and silence about a rule that did not run must not read as a rule that held.
        assertTrue(RuleEffects.fromSelfReference(List.of(), RuleEffects.Moment.RESULT)
                .isEmpty());
        assertTrue(RuleEffects.fromSelfReference(null, RuleEffects.Moment.RESULT).isEmpty());
    }

    @Test void aRunReportsEveryRuleThatCanNameWhatItAccountsFor() {
        List<RuleEffects.Effect> effects = RuleEffects.fromRun(
                FieldExpectations.inspect(model(FieldExpectation.EXPECTED), pool()),
                findings());

        assertEquals(3, effects.size(), "one expectation gap and two self-reference "
                + "decisions, worst first: " + effects.stream()
                .map(RuleEffects.Effect::title).toList());
        assertTrue(effects.get(0).size() >= effects.get(1).size(), "worst first");
    }

    @Test void theSilenceNamesWhatItDidAndDidNotLookAt() {
        assertTrue(RuleEffects.NOTHING_REPORTED.contains("self-reference"),
                RuleEffects.NOTHING_REPORTED);
        assertTrue(RuleEffects.NOTHING_REPORTED.contains("do not report here yet"),
                "and says the rest of the run is not covered: "
                        + RuleEffects.NOTHING_REPORTED);
    }

    @Test void aRuleThatHoldsGetsNoBucket() {
        List<WikidataDynamicObject> complete = pool();
        complete.removeIf(o -> o.get("ceremony") == null);

        assertTrue(RuleEffects.of(model(FieldExpectation.EXPECTED), complete).isEmpty(),
                "a bucket you cannot open is worse than silence");
        assertEquals("", RuleEffects.summary(List.of()));
    }

    @Test void theTitleCarriesItsOwnCountSoATabReadsUnopened() {
        RuleEffects.Effect effect =
                RuleEffects.of(model(FieldExpectation.EXPECTED), pool()).get(0);

        assertEquals("Nomination.ceremony is expected (2)", effect.title());
    }

    @Test void noExpectationsMeansNoBucketsRatherThanAnEmptyOne() {
        assertTrue(RuleEffects.of(model(FieldExpectation.NONE), pool()).isEmpty());
        assertTrue(RuleEffects.of(null, pool()).isEmpty());
        assertTrue(RuleEffects.of(model(FieldExpectation.EXPECTED), null).isEmpty());
    }

    @Test void completedRequiredCoverageStillNamesTheInstancesAlreadyDropped() {
        // The real path, not a simulated one: apply() is what runs during finalization
        // and what puts its coverage on the run, so it is apply()'s coverage that has to
        // keep the instances it just deleted. A test that measured with inspect() and
        // removed them by hand proves fromCoverage works and leaves the invariant the
        // bug actually depended on unasserted — clear the lists inside apply() to save
        // memory and every test would still pass while the result went silent again.
        List<WikidataDynamicObject> pool = pool();

        FieldExpectations.Result applied =
                FieldExpectations.apply(model(FieldExpectation.REQUIRED), pool, null);

        assertEquals(2, pool.size(), "apply really did remove them from the pool");
        RuleEffects.Effect result = RuleEffects.fromCoverage(
                applied.coverage(), RuleEffects.Moment.RESULT).getFirst();

        assertEquals(List.of("n3", "n4"), result.instances().stream()
                .map(objectview.Viewable::getIdentifier).toList(),
                "and the result can still name them, which is the whole point of "
                        + "reading recorded coverage instead of re-inspecting the pool");
        assertTrue(result.detail().contains("were dropped"), result.detail());
    }

    @Test void reInspectingTheFinishedPoolIsWhatWentWrong() {
        // Kept as the counter-example, because the failure is silent and plausible:
        // asking the finished pool reports that the rule holds, precisely because it
        // did not.
        List<WikidataDynamicObject> pool = pool();
        FieldExpectations.apply(model(FieldExpectation.REQUIRED), pool, null);

        assertTrue(RuleEffects.of(model(FieldExpectation.REQUIRED), pool).isEmpty(),
                "re-inspection finds nothing to report — a clean bill of health issued "
                        + "for the removal of the evidence");
    }

    @Test void completedExpectedCoverageUsesResultTenseWithoutClaimingAChange() {
        RuleEffects.Effect result = RuleEffects.fromCoverage(
                FieldExpectations.inspect(model(FieldExpectation.EXPECTED), pool()),
                RuleEffects.Moment.RESULT).getFirst();

        assertEquals(RuleEffects.Kind.FLAGGED, result.kind());
        assertTrue(result.detail().contains("were kept"), result.detail());
    }
}
