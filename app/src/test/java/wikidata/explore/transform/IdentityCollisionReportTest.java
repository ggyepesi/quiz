package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.CanonicalSpec;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A key is a decision about grain; a collision is that decision's consequence.
 *
 * <p>It used to have none visible. Two records sharing a key became one — dropped under
 * KEEP_ONE, folded in under MERGE_RECORDS — with no count and no log line, so a key too
 * coarse for its data shrank the population and the only symptom was a smaller number in
 * counts.tsv. History has 179 office holdings over 173 distinct subject/object pairs, so
 * keying on those alone loses six records and says nothing.
 */
class IdentityCollisionReportTest {

    private static WikidataDynamicObject record(String qid, String source, String value) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, qid);
        o.type("Holding");
        o.put("source", source);
        o.put("position", value);
        return o;
    }

    private static ReifyConstruct reify(CanonicalSpec.DuplicatePolicy policy) {
        return new ReifyConstruct("Person", "__Holding", "Holding", "source", "position",
                true, List.of(), List.of("source", "position"), "", List.of(), policy);
    }

    @Test void recordsThatSharedAnIdentityAreReported() {
        TransformEngine engine = new TransformEngine();
        WikidataDynamicObject person = new WikidataDynamicObject("Q1", "Someone");
        person.type("Person");
        person.put("__Holding", new java.util.ArrayList<>(List.of(
                record("S1", "Q1", "Q9"),
                record("S2", "Q1", "Q9"),      // same subject and object: one holding lost
                record("S3", "Q1", "Q8"))));

        engine.applyReify(new java.util.ArrayList<>(List.of(person)), reify(CanonicalSpec.DuplicatePolicy.KEEP_ONE));

        List<TransformEngine.IdentityCollision> collisions = engine.identityCollisions();
        assertEquals(1, collisions.size(), collisions.toString());
        assertEquals("Holding", collisions.get(0).type());
        assertTrue(collisions.get(0).kept() != collisions.get(0).collapsed(),
                "the two sides of a collision are different records");
    }

    @Test void aMergeIsReportedAsSuchRatherThanAsALoss() {
        TransformEngine engine = new TransformEngine();
        WikidataDynamicObject person = new WikidataDynamicObject("Q1", "Someone");
        person.type("Person");
        person.put("__Holding", new java.util.ArrayList<>(List.of(
                record("S1", "Q1", "Q9"),
                record("S2", "Q1", "Q9"))));

        engine.applyReify(new java.util.ArrayList<>(List.of(person)),
                reify(CanonicalSpec.DuplicatePolicy.MERGE_RECORDS));

        assertEquals(1, engine.identityCollisions().size());
        assertTrue(engine.identityCollisions().get(0).merged(),
                "Nobel MEANS to collapse — a merge is a fact, not a defect");
    }

    @Test void aKeyThatSeparatesEverythingReportsNothing() {
        TransformEngine engine = new TransformEngine();
        WikidataDynamicObject person = new WikidataDynamicObject("Q1", "Someone");
        person.type("Person");
        person.put("__Holding", new java.util.ArrayList<>(List.of(
                record("S1", "Q1", "Q9"),
                record("S2", "Q1", "Q8"))));

        engine.applyReify(new java.util.ArrayList<>(List.of(person)), reify(CanonicalSpec.DuplicatePolicy.KEEP_ONE));

        assertEquals(List.of(), engine.identityCollisions());
    }
}
