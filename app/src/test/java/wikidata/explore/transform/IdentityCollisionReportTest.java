package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.CanonicalSpec;

import java.util.List;
import java.util.Map;

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

    private static WikidataDynamicObject record(
            String qid, String source, String value, String note) {
        WikidataDynamicObject o = record(qid, source, value);
        o.put("note", note);
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

    @Test void wholeTransformRetainsAndLogsConflictsFromEveryReify() {
        TransformEngine engine = new TransformEngine();
        WikidataDynamicObject person = new WikidataDynamicObject("Q1", "Someone");
        person.type("Person");
        person.put("__First", new java.util.ArrayList<>(List.of(
                record("S1", "Q1", "Q9", "first-a"),
                record("S2", "Q1", "Q9", "first-b"))));
        person.put("__Second", new java.util.ArrayList<>(List.of(
                record("S3", "Q1", "Q8", "second-a"),
                record("S4", "Q1", "Q8", "second-b"))));

        TransformConfig config = new TransformConfig();
        config.reifies.add(reify("__First", "FirstHolding"));
        config.reifies.add(reify("__Second", "SecondHolding"));
        List<String> messages = new java.util.ArrayList<>();

        engine.apply(new java.util.ArrayList<>(List.of(person)), config, null,
                wikidata.explore.extract.GenerationLog.of(messages::add));

        assertEquals(2, engine.reductionConflicts().size(),
                "the second reify must not erase the first one's conflict");
        assertTrue(messages.stream().anyMatch(m -> m.contains("FirstHolding: 1 field reduction conflict")));
        assertTrue(messages.stream().anyMatch(m -> m.contains("SecondHolding: 1 field reduction conflict")));
    }

    private static ReifyConstruct reify(String listField, String targetType) {
        return new ReifyConstruct("Person", listField, targetType,
                "source", "position", true, List.of(), List.of("source", "position"),
                "", List.of(), CanonicalSpec.DuplicatePolicy.MERGE_RECORDS,
                Map.of("note", canonical.Reduction.REQUIRE_AGREEMENT));
    }
}
