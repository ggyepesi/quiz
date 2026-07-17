package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoolCopyTest {

    private static WikidataDynamicObject obj(String qid, String name, String type) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, name);
        o.type(type);
        return o;
    }

    @Test void deepCopyClonesGraphRewiresRefsAndSurvivesCycles() {
        WikidataDynamicObject film = obj("Q1", "Film", "Oscarnominations");
        WikidataDynamicObject cat = obj("Q2", "Best Picture", "Category");
        WikidataDynamicObject stmt = obj("Q1-ST", "stmt", "Statement");
        stmt.put("category", cat);
        stmt.put("source", film);          // cycle: stmt -> film -> stmt
        film.put("noms", List.of(stmt));

        List<WikidataDynamicObject> copy =
                PoolCopy.deepCopy(List.of(film, cat));

        WikidataDynamicObject filmC = copy.get(0);
        WikidataDynamicObject catC = copy.get(1);
        // fresh instances, same data
        assertNotSame(film, filmC);
        assertEquals("Q1", filmC.qid());
        assertEquals("Oscarnominations", filmC.typeName());

        // the statement was reached through the list field and cloned
        Object noms = filmC.get("noms");
        assertTrue(noms instanceof List<?>);
        WikidataDynamicObject stmtC = (WikidataDynamicObject) ((List<?>) noms).get(0);
        assertNotSame(stmt, stmtC);
        assertEquals("Q1-ST", stmtC.qid());

        // references rewired to the COPIES, not the originals
        assertSame(catC, stmtC.get("category"));
        assertSame(filmC, stmtC.get("source"));   // cycle handled

        // mutating a copy must not touch the original
        stmtC.type("Nomination");
        assertEquals("Statement", stmt.typeName());
    }
}
