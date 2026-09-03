package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One end of a triple is bounded ONE way.
 *
 * <p>It used to be two independent fields — explicit QIDs and a P31 type — and a model
 * could set both, whereupon the loader took the QIDs and the type filter did nothing
 * without saying so. The fix is not a documented precedence but a value that cannot be
 * in two states at once, so no precedence exists to be written down or got wrong.
 */
class EntityBoundTest {

    @Test void anEndIsBoundedOneWayAndTheOtherStatesAreUnrepresentable() {
        assertThrows(IllegalArgumentException.class,
                () -> new EntityBound(EntityBound.Kind.EXPLICIT, List.of("Q1"), "P31", false),
                "an explicit set is not also a relation");
        assertThrows(IllegalArgumentException.class,
                () -> new EntityBound(EntityBound.Kind.RELATION, List.of("Q1"), "", false),
                "a relation bound without a property is not a bound");
        assertThrows(IllegalArgumentException.class,
                () -> new EntityBound(EntityBound.Kind.UNBOUNDED, List.of("Q1"), "", false),
                "an unbounded end carries no values");
        assertThrows(IllegalArgumentException.class,
                () -> new EntityBound(EntityBound.Kind.EXPLICIT, List.of(), "", false),
                "a bounded end with nothing in it is not bounded");
    }

    @Test void anEmptyOrNonQidSetIsUnboundedRatherThanAnEmptyBound() {
        assertFalse(EntityBound.explicit(List.of()).bounded());
        assertFalse(EntityBound.explicit(List.of("", "not-a-qid")).bounded());
        assertFalse(EntityBound.instancesOf("").bounded(),
                "no type is no bound — not a bound onto nothing, which would admit nothing");
    }

    @Test void aTypeBoundIsARelationOnP31() {
        EntityBound bound = EntityBound.instancesOf("Q5");

        assertEquals(EntityBound.Kind.RELATION, bound.kind());
        assertEquals("P31", bound.relationPid());
        assertEquals(List.of("Q5"), bound.qids());
    }

    @Test void duplicatesCollapseAndOrderSurvives() {
        EntityBound bound = EntityBound.explicit(List.of("Q30", " Q20 ", "Q30", "Q10"));

        assertEquals(List.of("Q30", "Q20", "Q10"), bound.qids(),
                "order is a VALUES clause's order, so it must not be re-sorted");
    }

    @Test void anUnboundedEndHasNothingToRequest() {
        assertTrue(EntityBound.unbounded().toRequest("wikidata").isEmpty(),
                "nothing to request is not the same as requesting nothing");
    }

    @Test void aBoundCrossesToWhatAProviderIsAskedToFetch() {
        var explicit = EntityBound.explicit(List.of("Q102427"))
                .toRequest("wikidata").orElseThrow();
        assertEquals(datasource.api.acquisition.PopulationRequest.Kind.EXPLICIT,
                explicit.kind());
        assertEquals("", explicit.relationId());

        var relation = EntityBound.relation("P279", List.of("Q5"), true)
                .toRequest("wikidata").orElseThrow();
        assertEquals(datasource.api.acquisition.PopulationRequest.Kind.RELATION,
                relation.kind());
        assertEquals("P279", relation.relationId());
        assertTrue(relation.includeDescendants(),
                "P279 closure the single-QID type filter could not express at all");
    }
}
