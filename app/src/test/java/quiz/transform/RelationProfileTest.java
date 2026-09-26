package quiz.transform;

import objectview.Viewable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each shape a relation can really have, measured rather than asserted.
 *
 * <p>The profile exists because "is this an equivalence relation" is the wrong question —
 * succession, subclass and spouse are three different algebras and only one of them
 * partitions anything. What a grouping needs to know is measured here: component sizes,
 * whether a representative rule is well defined, and whether the component is complete.
 */
class RelationProfileTest {

    @Test void aSuccessionChainIsAnAcyclicComponentWithOneEndAtEachSide() {
        DynamicViewable viceroy = office("viceroy", "Viceroy of India");
        DynamicViewable governor = office("governor", "Governor-General of India");
        DynamicViewable president = office("president", "President of India");
        // Each office replaces the one before it, stated from both sides.
        governor.put("replaces", List.of(viceroy));
        viceroy.put("replacedBy", List.of(governor));
        president.put("replaces", List.of(governor));
        governor.put("replacedBy", List.of(president));

        RelationProfile profile = RelationProfile.of(
                List.of(viceroy, governor, president), "replaces", "replacedBy");

        assertEquals(2, profile.edges());
        assertEquals(2, profile.statedBothWays());
        assertEquals(List.of(), profile.statedOneWay());
        assertEquals(0, profile.reflexive());
        assertEquals(2, profile.symmetryBreaks());
        assertEquals(2, profile.symmetryBreakSamples().size());
        assertEquals(List.of(), profile.mutuallyReachable());
        assertEquals(1, profile.maxOutDegree(), "a succession is functional");
        assertEquals(1, profile.components().size());

        RelationProfile.Component chain = profile.components().getFirst();
        assertEquals(3, chain.size());
        assertFalse(chain.touchesBoundary());
        assertEquals(List.of("President of India"), names(chain.origins()));
        assertEquals(List.of("Viceroy of India"), names(chain.terminals()),
                "exactly one end each way is what makes 'the last office' a usable name");
        assertEquals(1, profile.transitivityBreaks(),
                "president -> governor -> viceroy without president -> viceroy: "
                        + "a chain is not transitive, and that is the point");
    }

    @Test void whatOnlyOneSideStatesIsCountedRatherThanQuietlyAccepted() {
        DynamicViewable earlier = office("earlier", "Earlier office");
        DynamicViewable later = office("later", "Later office");
        later.put("replaces", List.of(earlier));   // and no matching replacedBy

        RelationProfile profile = RelationProfile.of(
                List.of(earlier, later), "replaces", "replacedBy");

        assertEquals(1, profile.edges());
        assertEquals(0, profile.statedBothWays());
        assertEquals(1, profile.statedOneWay().size(),
                "Wikidata routinely records one direction; the gap is the curation item");
        RelationProfile.OneSided gap = profile.statedOneWay().getFirst();
        assertEquals("Later office", gap.from().getDisplayName());
        assertEquals("Earlier office", gap.to().getDisplayName());
        assertTrue(gap.forwardOnly(),
                "replaces says it and replacedBy does not, which is the edit to make");
        assertEquals(1, profile.components().size());
        assertEquals(2, profile.components().getFirst().size(),
                "one stated direction still connects the pair");
    }

    @Test void aSymmetricRelationIsNotTransitiveAndSaysSo() {
        DynamicViewable first = office("a", "Ann");
        DynamicViewable second = office("b", "Bo");
        DynamicViewable third = office("c", "Cy");
        first.put("sibling", List.of(second));
        second.put("sibling", List.of(first, third));
        third.put("sibling", List.of(second));

        RelationProfile profile = RelationProfile.of(
                List.of(first, second, third), "sibling", "");

        assertEquals(4, profile.edges());
        assertEquals(0, profile.symmetryBreaks(), "every stated edge is reciprocated");
        assertEquals(0, profile.statedBothWays(),
                "a single property has no second side to agree with");
        assertEquals(List.of(), profile.statedOneWay(),
                "and nothing to be one-sided about either");
        assertEquals(2, profile.transitivityBreaks(),
                "a<->b<->c leaves a<->c unstated, so the sibship is not closed");
        assertEquals(1, profile.components().size());
        assertEquals(3, profile.components().getFirst().size());
        assertEquals(1, profile.mutuallyReachable().size(),
                "a symmetric relation IS mutually reachable: here the class is the "
                        + "sibship, the same answer the component gives");
        assertEquals(3, profile.mutuallyReachable().getFirst().size());
    }

    @Test void aLoopIsReportedAsAStronglyConnectedComponent() {
        DynamicViewable first = office("x", "X");
        DynamicViewable second = office("y", "Y");
        DynamicViewable third = office("z", "Z");
        first.put("superClasses", List.of(second));
        second.put("superClasses", List.of(third));
        third.put("superClasses", List.of(first));

        RelationProfile profile = RelationProfile.of(
                List.of(first, second, third), "superClasses", "");

        assertEquals(1, profile.mutuallyReachable().size(),
                "the same measure, and for an order-like relation it is a data error: "
                        + "X is a superclass of itself through Y and Z");
        assertEquals(3, profile.mutuallyReachable().getFirst().size());
        assertTrue(profile.components().getFirst().origins().isEmpty(),
                "and it leaves no end to name the component after");
    }

    @Test void aChainLeavingTheLoadedPopulationSaysItIsUnfinished() {
        DynamicViewable inside = office("inside", "Loaded office");
        DynamicViewable outside = office("outside", "Office nobody loaded");
        inside.put("replaces", List.of(outside));

        RelationProfile profile = RelationProfile.of(
                List.of(inside), "replaces", "replacedBy");

        assertEquals(0, profile.edges(), "an edge needs two loaded ends");
        assertEquals(1, profile.danglingEdges());
        assertEquals(List.of(inside), profile.leavingPopulation(),
                "the member whose chain leaves is the one to expand the population from");
        assertTrue(profile.components().getFirst().touchesBoundary(),
                "a component may continue outside the population, and a group built "
                        + "from it would be a fragment that looks whole");
    }

    @Test void aRelationWhoseComponentsAreAllSingletonsPartitionsNothing() {
        DynamicViewable lonely = office("one", "One");
        DynamicViewable other = office("two", "Two");

        RelationProfile profile = RelationProfile.of(
                List.of(lonely, other), "replaces", "replacedBy");

        assertTrue(profile.partitionsNothing());
        assertEquals(1, profile.largestComponent());
    }

    /**
     * Witnessed by the findings that are actually offered — a statement only one side
     * makes, and a chain leaving the population. A member merely taking part in the
     * relation is not a witness, or a chain with nothing wrong with it would produce a
     * witness section for every office in it.
     */
    @Test void findingWitnessesAreOriginalInstancesAndExcludeUninvolvedMembers() {
        DynamicViewable later = office("later", "Later office");
        DynamicViewable earlier = office("earlier", "Earlier office");
        DynamicViewable leaving = office("leaving", "Leaves the population");
        DynamicViewable involvedButFine = office("fine", "Reciprocated both ways");
        DynamicViewable itsPredecessor = office("pred", "Its predecessor");
        later.put("replaces", List.of(earlier));
        leaving.put("replaces", List.of(office("out", "Never loaded")));
        involvedButFine.put("replaces", List.of(itsPredecessor));
        itsPredecessor.put("replacedBy", List.of(involvedButFine));

        RelationProfile profile = RelationProfile.of(
                List.of(later, earlier, leaving, involvedButFine, itsPredecessor),
                "replaces", "replacedBy");

        assertEquals(List.of(later, earlier, leaving), profile.findingWitnesses(),
                "the findings view shows the loaded witness objects, not every instance");
    }

    private static List<String> names(List<Viewable> values) {
        return values.stream().map(Viewable::getDisplayName).toList();
    }

    private static DynamicViewable office(String id, String name) {
        DynamicViewable value = new DynamicViewable(id, name);
        value.type("Position");
        return value;
    }
}
