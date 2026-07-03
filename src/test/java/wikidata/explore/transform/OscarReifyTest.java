package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OscarReifyTest {

    private static WikidataDynamicObject obj(String qid, String name, String type) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, name);
        o.type(type);
        return o;
    }

    @Test void filmAndPersonSidesCollapseToOneNomination() {
        // The same nomination, denormalized onto both endpoints.
        WikidataDynamicObject film = obj("Q214013", "21 Grams", "Oscarnominations");
        WikidataDynamicObject watts = obj("Q132616", "Naomi Watts", "Oscarnominations");
        WikidataDynamicObject bestActress = obj("Q103618", "Best Actress", "Award");

        // Film side: category + nominee (P2453) + year; NO forWork (film IS the work).
        WikidataDynamicObject filmStmt = obj("stmt-film", "Best Actress", "Statement");
        filmStmt.put("category", bestActress);
        filmStmt.put("nominee", watts);
        filmStmt.put("year", 2004);
        film.put("nominations", List.of(filmStmt));

        // Person side: category + forWork (P1686) + year; NO nominee (she IS it).
        WikidataDynamicObject personStmt = obj("stmt-person", "Best Actress", "Statement");
        personStmt.put("category", bestActress);
        personStmt.put("forWork", film);
        personStmt.put("year", 2004);
        watts.put("nominations", List.of(personStmt));

        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(film, watts));

        ReifyConstruct reify = new ReifyConstruct(
                "Oscarnominations", "nominations", "Oscar", "source", "value", true,
                List.of(
                        new ReifyConstruct.Role("nominee", "nominee", true),
                        new ReifyConstruct.Role("work", "forWork", true)),
                List.of("nominee", "category", "work", "year"));

        List<WikidataDynamicObject> created =
                new TransformEngine().applyReify(pool, reify);

        // Two denormalized statements → ONE canonical Oscar event.
        assertEquals(1, created.size(), "film+person sides should dedup to one");
        WikidataDynamicObject oscar = created.get(0);
        assertSame(watts, oscar.get("nominee"), "nominee = person (qualifier ∨ subject)");
        assertSame(film, oscar.get("work"), "work = film (forWork ∨ subject)");
        assertSame(bestActress, oscar.get("category"));
        assertEquals(2004, oscar.get("year"));
        assertEquals("Oscar", oscar.typeName());

        // The dropped duplicate must be DEMOTED, not merely absent from the return
        // list: it's still in the pool + stamped in place, so unless it's unstamped
        // it re-surfaces as a second served Oscar. Exactly one statement stays
        // "Oscar"; the other reverts to the anonymous sentinel.
        long served = pool.stream()
                .filter(o -> "Oscar".equals(o.typeName())).count();
        assertEquals(1, served, "only the kept event is served as Oscar");
        long demoted = List.of(filmStmt, personStmt).stream()
                .filter(o -> "WikidataDynamicObject".equals(o.typeName())).count();
        assertEquals(1, demoted, "the dropped duplicate is un-stamped");
    }

    @Test void workCategoryNominationKeepsFilmAsNominee() {
        // Best Picture: the film is the nominee (no nominee qualifier).
        WikidataDynamicObject film = obj("Q47703", "Chicago", "Oscarnominations");
        WikidataDynamicObject bestPicture = obj("Q102427", "Best Picture", "Award");
        WikidataDynamicObject stmt = obj("stmt-bp", "Best Picture", "Statement");
        stmt.put("category", bestPicture);
        stmt.put("year", 2003);
        film.put("nominations", List.of(stmt));

        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(film));
        ReifyConstruct reify = new ReifyConstruct(
                "Oscarnominations", "nominations", "Oscar", "source", "value", true,
                List.of(
                        new ReifyConstruct.Role("nominee", "nominee", true),
                        new ReifyConstruct.Role("work", "forWork", true)),
                List.of("nominee", "category", "work", "year"));

        List<WikidataDynamicObject> created =
                new TransformEngine().applyReify(pool, reify);

        assertEquals(1, created.size());
        assertSame(film, created.get(0).get("nominee"), "film is its own nominee");
        assertSame(film, created.get(0).get("work"));
    }

    // --- canonicalize-by-list: shared award vs separate nominations ---

    private static ReifyConstruct byNomineeList() {
        // nominees = the shared-award list (canonical marker); forWork = a single
        // inverse role (subject-fallback) that flags the denormalized person copy.
        return new ReifyConstruct(
                "Oscarnominations", "noms", "Oscar", "source", "value", true,
                List.of(new ReifyConstruct.Role("forWork", "forWork", true)),
                List.of(), "nominees");
    }

    @Test void sharedAwardIsOneNominationWithAllCoNominees() {
        WikidataDynamicObject film = obj("Q223299", "The Color Purple", "Oscarnominations");
        WikidataDynamicObject score = obj("Qscore", "Best Original Score", "Award");
        WikidataDynamicObject a = obj("Qa", "Quincy Jones", "Oscarnominations");
        WikidataDynamicObject b = obj("Qb", "Rod Temperton", "Oscarnominations");

        // Film-side shared statement: ONE statement, both composers as nominees.
        WikidataDynamicObject filmStmt = obj("st-score", "Best Original Score", "Statement");
        filmStmt.put("category", score);
        filmStmt.put("year", 1986);
        filmStmt.merge("nominees", a);
        filmStmt.merge("nominees", b);
        film.put("noms", List.of(filmStmt));

        // Person-side denormalized copies: forWork = film, no nominee list.
        WikidataDynamicObject aStmt = obj("st-a", "Best Original Score", "Statement");
        aStmt.put("category", score); aStmt.put("year", 1986); aStmt.put("forWork", film);
        a.put("noms", List.of(aStmt));
        WikidataDynamicObject bStmt = obj("st-b", "Best Original Score", "Statement");
        bStmt.put("category", score); bStmt.put("year", 1986); bStmt.put("forWork", film);
        b.put("noms", List.of(bStmt));

        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(film, a, b));
        List<WikidataDynamicObject> created =
                new TransformEngine().applyReify(pool, byNomineeList());

        assertEquals(1, created.size(), "shared award → ONE nomination");
        Object nominees = created.get(0).get("nominees");
        assertTrue(nominees instanceof List, "co-nominees kept as a list");
        assertEquals(2, ((List<?>) nominees).size(), "both composers retained");
        assertSame(film, created.get(0).get("forWork"));
        assertEquals(1, pool.stream().filter(o -> "Oscar".equals(o.typeName())).count(),
                "the person-side copies are dropped, not served");
        assertEquals("WikidataDynamicObject", aStmt.typeName());
        assertEquals("WikidataDynamicObject", bStmt.typeName());
    }

    @Test void separateNominationsInSameCategoryStaySeparate() {
        // Two supporting-actress nominations for one film = two film-side statements.
        WikidataDynamicObject film = obj("Q223299", "The Color Purple", "Oscarnominations");
        WikidataDynamicObject cat = obj("Qssa", "Best Supporting Actress", "Award");
        WikidataDynamicObject oprah = obj("Qo", "Oprah Winfrey", "Oscarnominations");
        WikidataDynamicObject margaret = obj("Qm", "Margaret Avery", "Oscarnominations");

        WikidataDynamicObject s1 = obj("st-1", "Best Supporting Actress", "Statement");
        s1.put("category", cat); s1.put("year", 1986); s1.merge("nominees", oprah);
        WikidataDynamicObject s2 = obj("st-2", "Best Supporting Actress", "Statement");
        s2.put("category", cat); s2.put("year", 1986); s2.merge("nominees", margaret);
        film.put("noms", List.of(s1, s2));

        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(film));
        List<WikidataDynamicObject> created =
                new TransformEngine().applyReify(pool, byNomineeList());

        assertEquals(2, created.size(), "two independent nominations stay separate");
    }

    @Test void workLessNominationMakesSubjectTheNominee() {
        // Honorary award to a person: no work-side statement, no forWork, no nominee
        // qualifier → the subject is the sole nominee.
        WikidataDynamicObject person = obj("Qh", "Honoree", "Oscarnominations");
        WikidataDynamicObject cat = obj("Qhon", "Honorary Award", "Award");
        WikidataDynamicObject stmt = obj("st-h", "Honorary Award", "Statement");
        stmt.put("category", cat); stmt.put("year", 1986);
        person.put("noms", List.of(stmt));

        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(person));
        List<WikidataDynamicObject> created =
                new TransformEngine().applyReify(pool, byNomineeList());

        assertEquals(1, created.size());
        assertSame(person, created.get(0).get("nominees"), "subject is the nominee");
    }
}
