package wikidata.explore.query.result;

import objectview.Viewable;
import objectview.field.FieldSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What points AT an object, read off the edges the result already walks.
 *
 * <p>A card shows what it points to; nothing showed what points to it. For a class with
 * no population of its own that is the more useful direction — a Nobel prize lists the
 * award records it grouped, but a record could not say which prize took it, which is the
 * connection a modeller checking a key wants to follow.
 */
class ReferrersReadTheEdgesBackwardsTest {

    static final class Award implements Viewable {
        private final String id;
        Award(String id) { this.id = id; }
        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return id; }
        @Override public String typeName() { return "LaureatesWithMotivation"; }
        @Override public FieldSet fields() { return FieldSet.of(this); }
    }

    static final class Prize implements Viewable {
        private final String id;
        public List<Award> laureatesWithMotivation = new ArrayList<>();
        public Award headline;
        Prize(String id) { this.id = id; }
        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return id; }
        @Override public String typeName() { return "NobelPrize"; }
        @Override public FieldSet fields() { return FieldSet.of(this); }
    }

    private static ObjectQueryResult resultOf(List<Viewable> roots) {
        return new ObjectQueryResult(roots, Prize.class, "test");
    }

    /** The edge a collection field makes, seen from the other end. */
    @Test void aGroupedRecordSaysWhichGroupTookIt() {
        Award einstein = new Award("a1");
        Prize physics = new Prize("Physics 1921");
        physics.laureatesWithMotivation.add(einstein);

        Map<Viewable, List<ObjectQueryResult.Referrer>> referrers =
                resultOf(List.of(physics)).referrers();

        assertEquals(1, referrers.get(einstein).size());
        assertSame(physics, referrers.get(einstein).getFirst().owner());
        assertEquals("laureatesWithMotivation",
                referrers.get(einstein).getFirst().field(),
                "which edge, not just which object — a card reached by two edges of the "
                        + "same type is the case worth naming");
    }

    /** Two edges from the same owner are two facts, not one. */
    @Test void everyEdgeIsRecordedEvenBetweenTheSamePair() {
        Award einstein = new Award("a1");
        Prize physics = new Prize("Physics 1921");
        physics.laureatesWithMotivation.add(einstein);
        physics.headline = einstein;

        List<ObjectQueryResult.Referrer> pointing =
                resultOf(List.of(physics)).referrers().get(einstein);

        assertEquals(2, pointing.size(), "grouped by it AND headlined by it");
        assertEquals(List.of("headline", "laureatesWithMotivation"),
                pointing.stream().map(ObjectQueryResult.Referrer::field).sorted().toList());
    }

    /** An object nothing points at has no entry, rather than an empty one. */
    @Test void aRootNothingPointsAtIsNotListed() {
        Prize physics = new Prize("Physics 1921");

        assertNull(resultOf(List.of(physics)).referrers().get(physics),
                "a card with nothing pointing at it shows no \"used by\" at all");
    }

    /** Two objects that compare equal are still two places in the graph. */
    @Test void referrersAreKeyedByIdentityNotEquality() {
        Award first = new Award("a1");
        Award same = new Award("a1");
        Prize physics = new Prize("Physics 1921");
        physics.laureatesWithMotivation.add(first);

        Map<Viewable, List<ObjectQueryResult.Referrer>> referrers =
                resultOf(List.of(physics, same)).referrers();

        assertNotNull(referrers.get(first));
        assertNull(referrers.get(same),
                "a card is shown for one of them, and only that one is pointed at");
    }

    /** Both questions come off one traversal, so they cannot disagree. */
    @Test void theSameWalkAnswersTypesAndReferrers() {
        Award einstein = new Award("a1");
        Prize physics = new Prize("Physics 1921");
        physics.laureatesWithMotivation.add(einstein);
        ObjectQueryResult result = resultOf(List.of(physics));

        assertEquals(1, result.countOf("LaureatesWithMotivation"),
                "reached through a field, and counted");
        assertNotNull(result.referrers().get(einstein),
                "reached through a field, and recorded as reached");
    }
}
