package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applying a graph result narrows a class to the population the graph accepted, and a
 * population is a set of identities.
 *
 * <p>What went wrong: the apply path removed every instance of the output class from the
 * pool and put the run's candidate objects in their place. A candidate carries a QID, a
 * label and a reverse reference to its annotation record and nothing else, so one Apply
 * turned 1317 generated Positions into 1307 shells whose only field was
 * "Graph annotation" — superClasses, jurisdiction, inception, abolished, replaces,
 * replacedBy and countries gone from every one — and pulled all 1307 annotation records
 * into the domain snapshot behind that reference, where they were counted as domain data
 * (counts.tsv read total=2615 against the previous total=1317).
 *
 * <p>So the assertions are about what SURVIVES, not about the count: the kept instances
 * must be the pool's own objects, whole.
 */
class NarrowedPoolTest {

    private static WikidataDynamicObject position(String qid, String jurisdiction) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, "office " + qid);
        value.type("Position");
        value.put("jurisdiction", jurisdiction);
        return value;
    }

    /** What the graph produces for a reached entity: an id, a label, a back-reference. */
    private static WikidataDynamicObject candidate(String qid) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, "office " + qid);
        value.type("Position");
        value.put("Graph annotation", new WikidataDynamicObject(qid, "record " + qid));
        return value;
    }

    @Test void anAcceptedInstanceKeepsEveryFieldAcquisitionGaveIt() {
        WikidataDynamicObject kept = position("Q1", "Spain");
        GenerationRuns.NarrowedPool narrowed = GenerationRuns.narrowedTo(
                List.of(kept), "Position", Set.of("Q1"));

        assertEquals(1, narrowed.pool().size());
        assertSame(kept, narrowed.pool().get(0),
                "the surviving instance is the pool's own object, not a stand-in for it");
        assertEquals("Spain", narrowed.pool().get(0).get("jurisdiction"));
        assertEquals(Set.of("Q1"), narrowed.kept());
    }

    @Test void aCandidateNeverEntersThePoolInPlaceOfAnInstance() {
        WikidataDynamicObject generated = position("Q1", "Spain");
        GenerationRuns.NarrowedPool narrowed = GenerationRuns.narrowedTo(
                List.of(generated), "Position", Set.of("Q1"));

        WikidataDynamicObject survivor = narrowed.pool().get(0);
        assertEquals(null, survivor.get("Graph annotation"),
                "narrowing selects among instances; it never substitutes the run's "
                        + "candidate objects, which would carry the annotation into the "
                        + "domain snapshot with them");
        assertTrue(narrowed.pool().stream().noneMatch(
                        value -> value.get("Graph annotation") != null),
                "no annotation reference reaches the pool");
        // The shape the bug produced, stated so it cannot come back unnoticed.
        assertEquals("Spain", survivor.get("jurisdiction"));
        assertTrue(candidate("Q1").get("Graph annotation") != null,
                "a candidate really does carry the back-reference — that is why "
                        + "substituting one loses the instance and gains the record");
    }

    @Test void aRejectedInstanceIsDroppedAndOtherClassesAreUntouched() {
        WikidataDynamicObject accepted = position("Q1", "Spain");
        WikidataDynamicObject rejected = position("Q2", "Rome");
        WikidataDynamicObject other = new WikidataDynamicObject("Q3", "a person");
        other.type("Person");

        GenerationRuns.NarrowedPool narrowed = GenerationRuns.narrowedTo(
                List.of(accepted, rejected, other), "Position", Set.of("Q1"));

        assertEquals(List.of(accepted, other), narrowed.pool(),
                "only the output class is narrowed; every other class passes through");
        assertEquals(Set.of("Q1"), narrowed.kept());
    }

    @Test void anAcceptedIdWithNoInstanceIsReportedRatherThanInvented() {
        GenerationRuns.NarrowedPool narrowed = GenerationRuns.narrowedTo(
                List.of(position("Q1", "Spain")), "Position", Set.of("Q1", "Q9"));

        assertEquals(1, narrowed.pool().size(),
                "an id the project has not generated stays an id: an empty stand-in is "
                        + "indistinguishable from an instance whose acquisition failed");
        assertEquals(Set.of("Q9"), narrowed.ungenerated());
    }
}
