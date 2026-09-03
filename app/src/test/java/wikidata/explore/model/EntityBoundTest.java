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
                () -> new EntityBound(EntityBound.Kind.EXPLICIT, List.of("Q1"), "P31", "", "", false),
                "an explicit set is not also a relation");
        assertThrows(IllegalArgumentException.class,
                () -> new EntityBound(EntityBound.Kind.RELATION, List.of("Q1"), "", "", "", false),
                "a relation bound without a property is not a bound");
        assertThrows(IllegalArgumentException.class,
                () -> new EntityBound(EntityBound.Kind.UNBOUNDED, List.of("Q1"), "", "", "", false),
                "an unbounded end carries no values");
        assertThrows(IllegalArgumentException.class,
                () -> new EntityBound(EntityBound.Kind.EXPLICIT, List.of(), "", "", "", false),
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

    /**
     * A vocabulary bound is a REFERENCE. Resolving it to its members here would freeze
     * them, so editing the vocabulary would stop reaching the ends bounded by it — the
     * same reason an import is a live reference and not a copy.
     */
    @Test void aVocabularyBoundNamesItsSelectionRatherThanCopyingIt() {
        EntityBound bound = EntityBound.vocabulary("OscarCategories");

        assertEquals(EntityBound.Kind.VOCABULARY, bound.kind());
        assertEquals("OscarCategories", bound.selectionName());
        assertEquals(List.of(), bound.qids(), "a reference carries no values of its own");
        assertTrue(bound.toRequest("wikidata").isEmpty(),
                "only the project knows a vocabulary's members, so compilation resolves "
                        + "it — this record cannot");
    }

    @Test void theOtherKindsDoNotNameASelection() {
        assertThrows(IllegalArgumentException.class,
                () -> new EntityBound(EntityBound.Kind.EXPLICIT, List.of("Q1"), "",
                        "OscarCategories", "", false));
        assertThrows(IllegalArgumentException.class,
                () -> new EntityBound(EntityBound.Kind.VOCABULARY, List.of("Q1"), "",
                        "OscarCategories", "", false),
                "a vocabulary bound is a reference, not a copy of its values");
        assertFalse(EntityBound.vocabulary("  ").bounded());
    }

    /**
     * Resolving a bound must not reshape one that needed no resolving.
     *
     * <p>Compilation used to take the authored bound apart into a QID list and a type
     * QID and rebuild it from those, so it could only express what those two variables
     * could: a RELATION on anything but P31 was dropped entirely, and includeDescendants
     * with it. A bound that is already executable comes back identical.
     */
    @Test void resolvingLeavesAnExecutableBoundExactlyAsItWas() {
        EntityBound viaSubclass = EntityBound.relation("P279", List.of("Q5"), true);
        assertEquals(viaSubclass, viaSubclass.resolved(List.of("Q1"), "Q2"),
                "a non-P31 relation survives, and so does its closure flag");

        EntityBound explicit = EntityBound.explicit(List.of("Q30", "Q20"));
        assertEquals(explicit, explicit.resolved(List.of("Q99"), "Q98"));
        assertEquals(EntityBound.unbounded(),
                EntityBound.unbounded().resolved(List.of("Q99"), ""));
    }

    @Test void aVocabularyResolvesToItsMembersOrItsType() {
        assertEquals(EntityBound.explicit(List.of("Q1", "Q2")),
                EntityBound.vocabulary("Categories").resolved(List.of("Q1", "Q2"), "Q9"),
                "members win: they are the tighter, deterministic bound (R16)");
        assertEquals(EntityBound.instancesOf("Q9"),
                EntityBound.vocabulary("Categories").resolved(List.of(), "Q9"),
                "a vocabulary with only a type still bounds by that type");
        assertEquals(EntityBound.unbounded(),
                EntityBound.vocabulary("Missing").resolved(List.of(), ""),
                "a vocabulary naming nothing bounds nothing — and says so");
    }
}
