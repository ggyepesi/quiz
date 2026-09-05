package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.EntityBound;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An end's bound survives being shown and read back.
 *
 * <p>It did not. The editor offered "Instances of" and built
 * {@code EntityBound.instancesOf(firstQid)} — P31, one target, no closure — while the
 * model's bound carries ANY property, ALL its targets, and an optional P279 closure. And
 * {@code bound()} is read on every apply whether or not anything was touched, so merely
 * visiting the panel reduced "subclasses of these three" to "instances of that one".
 *
 * <p>No shipped model was losing anything — Nobel's is the only bound and it is a
 * vocabulary — which is exactly why this needed a test rather than a bug report.
 */
class AnEndBoundKeepsWhatItWasGivenTest {

    private static EntityEndEditor showing(EntityBound bound) {
        EntityEndEditor editor = new EntityEndEditor("Subject", "what it restricts");
        editor.show(bound);
        return editor;
    }

    /** Any property, not P31 with another name. */
    @Test void aSubclassBoundIsStillASubclassBoundAfterATurn() {
        EntityBound subclasses =
                EntityBound.relation("P279", List.of("Q5", "Q95074"), false);

        EntityBound after = showing(subclasses).bound();

        assertEquals(EntityBound.Kind.RELATION, after.kind());
        assertEquals("P279", after.relationPid(), "the property the reader chose");
    }

    /** Every target, not the first one. */
    @Test void everyQidSurvives() {
        EntityBound three =
                EntityBound.relation("P31", List.of("Q5", "Q43229", "Q95074"), false);

        assertEquals(List.of("Q5", "Q43229", "Q95074"), showing(three).bound().qids());
    }

    /** The closure is a value, not a default. */
    @Test void theSubclassClosureSurvives() {
        EntityBound withClosure = EntityBound.relation("P31", List.of("Q5"), true);

        assertTrue(showing(withClosure).bound().includeDescendants(),
                "P279 closure is what the model says; the editor may not drop it");
    }

    /** The other kinds keep their shape too. */
    @Test void explicitAndVocabularyAndUnboundedAllReturn() {
        assertEquals(EntityBound.Kind.EXPLICIT,
                showing(EntityBound.explicit(List.of("Q5", "Q42"))).bound().kind());
        assertEquals(List.of("Q5", "Q42"),
                showing(EntityBound.explicit(List.of("Q5", "Q42"))).bound().qids());
        assertEquals(EntityBound.Kind.UNBOUNDED,
                showing(EntityBound.unbounded()).bound().kind());
    }

    /** A relation with no property is not a relation — it bounds nothing. */
    @Test void aRelationWithoutItsPropertyBoundsNothing() {
        EntityEndEditor editor = new EntityEndEditor("Subject", "what it restricts");
        editor.show(EntityBound.explicit(List.of("Q5")));

        assertEquals(EntityBound.Kind.EXPLICIT, editor.bound().kind(),
                "these QIDs, with no property, are just these QIDs");
    }
}
