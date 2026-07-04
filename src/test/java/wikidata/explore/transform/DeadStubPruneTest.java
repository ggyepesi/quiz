package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeadStubPruneTest {

    /** A dead-QID node: label never resolved (name == qid) and no fields. */
    private static WikidataDynamicObject deadStub(String qid) {
        return new WikidataDynamicObject(qid, null);   // name blank -> getDisplayName() == qid
    }

    @Test void identifiesUnlabelledFieldlessNodeAsDead() {
        assertTrue(DeadStubPrune.isDeadStub(deadStub("Q61283808")));
    }

    @Test void keepsBareReferenceThatResolvedALabel() {
        // No fields, but a real label -> a usable link, NOT a dead stub.
        WikidataDynamicObject human = new WikidataDynamicObject("Q5", "human");
        assertFalse(DeadStubPrune.isDeadStub(human));
    }

    @Test void keepsUnlabelledNodeThatCarriesFields() {
        WikidataDynamicObject o = new WikidataDynamicObject("Q123", null);
        o.put("someField", "value");
        assertFalse(DeadStubPrune.isDeadStub(o));
    }

    @Test void prunesAndUnlinksFromCollectionAndScalarRefs() {
        WikidataDynamicObject dead = deadStub("Q61283808");
        WikidataDynamicObject film = new WikidataDynamicObject("Q11424", "film");

        WikidataDynamicObject nominee = new WikidataDynamicObject("Q190631", "Bette Midler");
        nominee.type("OscarNominations");
        nominee.merge("type", film);     // collection ref, one dead one live
        nominee.merge("type", dead);
        nominee.put("scalarRef", dead);  // scalar ref to the dead stub

        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(nominee, film, dead));
        Set<WikidataDynamicObject> pruned = DeadStubPrune.apply(pool, null);

        assertEquals(1, pruned.size());
        assertTrue(pruned.contains(dead));

        // Collection kept the live ref, dropped the dead one.
        Object type = nominee.get("type");
        assertTrue(type instanceof List<?>);
        assertEquals(List.of(film), type);

        // Scalar ref to the dead stub was removed.
        assertFalse(nominee.dynamicFields().containsKey("scalarRef"));
    }
}
