package wikidata.explore.compiled;

import canonical.CanonicalizationPlan;
import canonical.KeyComponent;
import canonical.Reduction;
import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One plan per class, resolved once, with the defaults applied in exactly one place.
 *
 * <p>These read the SHIPPED models rather than fixtures, because the claim being tested
 * is about what real configuration means — and because a fixture would let the defaults
 * be checked against an example built to suit them.
 */
class CanonicalizationPlanTest {

    private static GeneratedProjectModel model(String domain) throws Exception {
        return new GeneratedProjectModelStore().load(
                new File("../data/wikidata/" + domain + "/" + domain + ".model.json"));
    }

    /**
     * The design's own acceptance criterion, reached from the defaults: Nobel's key is
     * category, year and motivation, so those are not reduced at all, and `laureates` is
     * the only field left — a collection, so a union. One field, nothing configured.
     */
    @Test void nobelAsksForNothingAndGetsWhatItMeans() throws Exception {
        var award = model("nobelprizes").findClass("LaureatesWithMotivation");
        CanonicalizationPlan plan = CanonicalizationPlans.of(award);

        assertEquals(
                List.of(KeyComponent.field("category"), KeyComponent.field("year"),
                        KeyComponent.field("motivation")),
                plan.key());
        assertEquals(Reduction.UNION_DISTINCT, plan.reductionFor("laureates"),
                "a shared prize is one award whose laureates combine");
        assertEquals(List.of("laureates"), List.copyOf(plan.reductionByField().keySet()),
                "and no key component is reduced: its value formed the partition");
        assertTrue(award.canonical().reductions().isEmpty(),
                "none of which is configured — it all follows from cardinality");
    }

    /** A scalar cannot union: it would produce a list the field cannot hold. */
    @Test void historyRequiresAgreementOnWhatItDidNotKey() throws Exception {
        var holding = model("history").findClass("OfficeHolding");
        CanonicalizationPlan plan = CanonicalizationPlans.of(holding);

        assertEquals(Reduction.REQUIRE_AGREEMENT, plan.reductionFor("predecessor"));
        assertEquals(Reduction.REQUIRE_AGREEMENT, plan.reductionFor("successor"));
    }

    /**
     * An entity class keys on its source identity, and an owned part on owner + site.
     * Both are what `Canonicalizer` already does by branching on the class kind; saying
     * them as components is what will let a modeller choose otherwise.
     */
    @Test void theIdentityRegimesBecomeNamedComponents() throws Exception {
        var person = model("history").findClass("Person");
        assertEquals(List.of(KeyComponent.sourceIdentity()),
                CanonicalizationPlans.of(person).key());

        var name = model("history").findClass("Name");
        assertEquals(List.of(KeyComponent.ownerSiteIdentity()),
                CanonicalizationPlans.of(name).key(),
                "a part is identified by where it was produced, not by its own values");
    }

    /** Every shipped class says what identifies it — nothing is left to a fallback. */
    @Test void everyShippedClassIsIdentified() throws Exception {
        for (String domain : List.of("nobelprizes", "oscarnominations", "history",
                "movies", "constellations", "periodictable", "mythology")) {
            for (var clazz : model(domain).classes()) {
                assertTrue(CanonicalizationPlans.of(clazz).identified(),
                        domain + "/" + clazz.className() + " has no key");
            }
        }
    }

    /** A configured reduction wins, and is visibly not a default. */
    @Test void aChosenReductionIsKeptAndKnownToBeChosen() throws Exception {
        var holding = model("history").findClass("OfficeHolding");
        holding.canonical().reductions().put("predecessor", Reduction.PREFER_NON_EMPTY);

        assertEquals(Reduction.PREFER_NON_EMPTY,
                CanonicalizationPlans.of(holding).reductionFor("predecessor"));
        assertTrue(CanonicalizationPlans.defaultedFields(holding).containsKey("successor"));
        assertTrue(!CanonicalizationPlans.defaultedFields(holding).containsKey("predecessor"),
                "what was chosen must be distinguishable from what was defaulted");
    }

    /**
     * An owned part keys on owner plus site, and is NOT pushed through content grouping.
     *
     * <p>Exactly one part exists per owner per production site — that is what producing
     * it per site means — so grouping by its field values would ask a question whose
     * answer is already guaranteed, and would ask it using values that can contradict
     * each other. A structural key is not a weaker key here; it is the true one.
     */
    @Test void anOwnedPartKeysStructurallyRatherThanByItsValues() throws Exception {
        var name = model("history").findClass("Name");
        CanonicalizationPlan plan = CanonicalizationPlans.of(name);

        assertEquals(List.of(KeyComponent.ownerSiteIdentity()), plan.key());
        assertTrue(plan.key().get(0).structural(),
                "production supplies it; no field of the part is consulted");
        assertTrue(plan.reductionByField().keySet().stream()
                        .noneMatch(field -> plan.key().contains(KeyComponent.field(field))),
                "and nothing of the key is reduced");
    }

    /**
     * An aggregate's identity is its canonical key, like every other construct's.
     *
     * <p>It was kept in AggregateClassSource as target/source pairs — a fourth place
     * identity was stored, and the reason the plan compiler needed a branch per
     * construct. What remains there is the half only an aggregate has: each of its own
     * fields is grouped from a differently-named field on the source, and applying that
     * rename is construction.
     */
    @Test void anAggregateKeysLikeEverythingElseAndRenamesLikeNothingElse() throws Exception {
        var prize = model("nobelprizes").findClass("NobelPrize");

        assertEquals(List.of(KeyComponent.field("category"), KeyComponent.field("year")),
                CanonicalizationPlans.of(prize).key(),
                "read from the canonical key, with no aggregate branch to read it");
        assertEquals(List.of("category", "year"), prize.canonical().keyFields(),
                "and a model saved before the move arrives with it there");

        assertEquals("category", prize.aggregateSource().sourceFieldFor("category"),
                "the rename table stays, because only an aggregate needs one");
    }
}
