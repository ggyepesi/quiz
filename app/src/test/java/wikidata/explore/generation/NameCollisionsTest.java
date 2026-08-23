package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which entities count as sharing a name.
 *
 * <p>A quiz answer is a name, so two entities called "Mercury" make a question with two right
 * answers — the run reports them and the reader disambiguates. The report is only usable
 * because of one rule that had never been run: a reified statement atom is keyed
 * {@code Q123-UUID} or {@code Q123__Q456} and is named on purpose by a field it carries, so a
 * person with forty nominations would be reported as a forty-way collision with themselves,
 * every run, drowning the real ones.
 */
class NameCollisionsTest {

    @Test void twoEntitiesUnderOneNameAreACollision() {
        List<NameCollisions.Collision> collisions = NameCollisions.detect(List.of(
                object("Q308", "Mercury"), object("Q1090", "Mercury"),
                object("Q111", "Venus")));

        assertEquals(1, collisions.size());
        assertEquals("Mercury", collisions.getFirst().name());
        assertEquals(List.of("Q308", "Q1090"), collisions.getFirst().qids());
        assertEquals(2, NameCollisions.entityCount(collisions));
    }

    /** The rule the whole report rests on. */
    @Test void aReifiedStatementSharingItsSubjectsNameIsNotACollision() {
        List<NameCollisions.Collision> collisions = NameCollisions.detect(List.of(
                object("Q42", "Meryl Streep"),
                object("Q42-3f9a1c", "Meryl Streep"),
                object("Q42__Q103360", "Meryl Streep"),
                object("Q42-77b2de", "Meryl Streep")));

        assertTrue(collisions.isEmpty(),
                "a nomination is named after its nominee on purpose: " + collisions);
    }

    @Test void aRealCollisionIsStillFoundAmongTheStatementsThatShareThoseNames() {
        List<NameCollisions.Collision> collisions = NameCollisions.detect(List.of(
                object("Q42", "Meryl Streep"), object("Q42-3f9a1c", "Meryl Streep"),
                object("Q9999", "Meryl Streep")));

        assertEquals(List.of("Q42", "Q9999"), collisions.getFirst().qids(),
                "the two real entities collide; the statement atom is not one of them");
    }

    /** A report truncated to N rows has to keep the worst of them. */
    /** Reachable through a field, never offered as an answer. */
    private static WikidataDynamicObject referent(String qid, String name) {
        return new WikidataDynamicObject(qid, name);
    }

    @Test void aReferentSharingANameIsNotAnAmbiguousAnswer() {
        // Person.structuredName pulls in the P735/P734 name entities, and Wikidata keeps
        // one item per name per language — so "Lee" the family name and "Lee" the given
        // name are different QIDs sharing a label. They collide by construction and
        // always will; on the Oscars domain they were 297 of 556 reported collisions.
        List<WikidataDynamicObject> pool = List.of(
                referent("Q2061957", "Lee"), referent("Q12794688", "Lee"),
                referent("Q11983535", "Lee"));

        assertTrue(NameCollisions.detect(pool).isEmpty(),
                "a warning whose whole value is being worth reading cannot be mostly "
                        + "names nobody can act on");
        assertEquals(1, NameCollisions.detectReferenced(pool).size(),
                "but they are still counted, because a referent is rendered wherever a "
                        + "field shows one");
    }

    @Test void aServedEntityAndAReferentSharingANameSplitBetweenTheTwoCounts() {
        List<WikidataDynamicObject> pool = List.of(
                object("Q1", "Lee"), referent("Q2", "Lee"));

        assertTrue(NameCollisions.detect(pool).isEmpty(),
                "one served entity is not ambiguous with something that is never offered");
        assertTrue(NameCollisions.detectReferenced(pool).isEmpty(),
                "and one referent is not ambiguous with something never in that list");
    }

    @Test void theBiggestCollisionComesFirst() {
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(
                object("Q1", "Pair"), object("Q2", "Pair")));
        for (int i = 0; i < 5; i++) pool.add(object("Q1" + i, "Crowd"));
        for (int i = 0; i < 3; i++) pool.add(object("Q2" + i, "Few"));

        List<NameCollisions.Collision> collisions = NameCollisions.detect(pool);

        assertEquals(List.of("Crowd", "Few", "Pair"),
                collisions.stream().map(NameCollisions.Collision::name).toList());
        assertEquals(10, NameCollisions.entityCount(collisions));
    }

    @Test void oneEntityNamedTwiceIsOneEntity() {
        assertTrue(NameCollisions.detect(List.of(
                object("Q308", "Mercury"), object("Q308", "Mercury"))).isEmpty(),
                "the same QID appearing twice in the pool is not two entities");
    }

    @Test void anythingWithoutBothANameAndAQidIsSkippedRatherThanGrouped() {
        assertTrue(NameCollisions.detect(Arrays.asList(
                object("Q1", null), object("Q2", null),
                object("Q3", "   "), object("Q4", "   "),
                object("", "Nameless"), object(null, "Nameless"),
                null)).isEmpty());
    }

    @Test void nothingToLookAtIsNoCollisions() {
        assertEquals(List.of(), NameCollisions.detect(null));
        assertEquals(List.of(), NameCollisions.detect(List.of()));
        assertEquals(0, NameCollisions.entityCount(null));
    }

    /** A SERVED entity: membership is the type stamp, and only members can be an
     *  answer. An unstamped object is a referent — see {@link #referent}. */
    private static WikidataDynamicObject object(String qid, String name) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, name);
        o.type("Thing");
        return o;
    }
}
