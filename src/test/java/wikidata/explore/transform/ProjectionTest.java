package wikidata.explore.transform;

import aux.FlexibleDate;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectionTest {

    private static WikidataDynamicObject typed(String qid, String name, String type) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, name);
        o.type(type);
        return o;
    }

    @Test
    void projectsAYearViewFromAReferentDateByPath() {
        WikidataDynamicObject edition = typed("Q1", "41st Academy Awards", "Edition");
        edition.put("date", new FlexibleDate(1969, 4, 14));   // day precision

        // Existing (P585-derived) year: it's the coercion sample AND the thing the
        // authoritative edition must OVERWRITE (disagreeing year 1968).
        WikidataDynamicObject wrong = typed("N1", "wrong year", "Nomination");
        wrong.put("edition", edition);
        wrong.put("year", new FlexibleDate(1968));

        WikidataDynamicObject absent = typed("N2", "no year yet", "Nomination");
        absent.put("edition", edition);

        // Extraction is the PATH: date.year -> the year view, coerced to the DATE field.
        new TransformEngine().applyProjection(
                List.of(wrong, absent, edition), "Nomination", "edition", "date.year", "year");

        assertEquals(new FlexibleDate(1969), wrong.get("year"));
        assertEquals(new FlexibleDate(1969), absent.get("year"));
    }

    @Test
    void reportsHowManyItChangedAndIsIdempotent() {
        WikidataDynamicObject edition = typed("Q1", "41st Academy Awards", "Edition");
        edition.put("date", new FlexibleDate(1969, 4, 14));

        WikidataDynamicObject wrong = typed("N1", "wrong year", "Nomination");
        wrong.put("edition", edition);
        wrong.put("year", new FlexibleDate(1968));      // disagrees -> changed
        WikidataDynamicObject absent = typed("N2", "no year", "Nomination");
        absent.put("edition", edition);                 // absent -> changed
        WikidataDynamicObject already = typed("N3", "right year", "Nomination");
        already.put("edition", edition);
        already.put("year", new FlexibleDate(1969));     // agrees -> NOT changed

        TransformEngine engine = new TransformEngine();
        List<WikidataDynamicObject> pool = List.of(wrong, absent, already, edition);

        int changed = engine.applyProjection(pool, "Nomination", "edition", "date.year", "year");
        assertEquals(2, changed, "only the wrong + absent years change; the agreeing one doesn't");

        // Re-Remap must report 0 changed, not phantom churn.
        int again = engine.applyProjection(pool, "Nomination", "edition", "date.year", "year");
        assertEquals(0, again, "projection is idempotent — a second pass changes nothing");
    }

    @Test
    void sameConstructCopiesTheWholeDateWithADifferentPath() {
        WikidataDynamicObject edition = typed("Q1", "41st", "Edition");
        edition.put("date", new FlexibleDate(1969, 4, 14));
        WikidataDynamicObject nom = typed("N1", "nom", "Nomination");
        nom.put("edition", edition);
        nom.put("ceremonyDate", new FlexibleDate(2000, 1, 1));   // sample, day precision

        // No view = the whole date, no extraction — the path decides, not the construct.
        new TransformEngine().applyProjection(
                List.of(nom, edition), "Nomination", "edition", "date", "ceremonyDate");

        assertEquals(new FlexibleDate(1969, 4, 14), nom.get("ceremonyDate"));
    }
}
