package wikidata.explore.transform;

import aux.FlexibleDate;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YearFromDateTest {

    private static WikidataDynamicObject typed(String qid, String name, String type) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, name);
        o.type(type);
        return o;
    }

    @Test
    void yearIsTheReferentDatesYearAtYearPrecision() {
        WikidataDynamicObject edition = typed("Q1", "41st Academy Awards", "Edition");
        edition.put("date", new FlexibleDate(1969, 4, 14));   // day precision

        WikidataDynamicObject absent = typed("Q2-x", "no year yet", "Nomination");
        absent.put("edition", edition);

        WikidataDynamicObject wrong = typed("Q3-x", "wrong year", "Nomination");
        wrong.put("edition", edition);
        wrong.put("year", new FlexibleDate(1968));            // disagrees — edition wins

        new TransformEngine().applyYearFromDate(
                List.of(absent, wrong, edition), "Nomination", "edition", "date", "year");

        assertEquals(new FlexibleDate(1969), absent.get("year"));
        assertEquals(new FlexibleDate(1969), wrong.get("year"));
        assertEquals(FlexibleDate.Precision.YEAR,
                ((FlexibleDate) absent.get("year")).precision());
    }

    @Test
    void residualWithoutAReferentDateIsNormalisedToYearPrecision() {
        WikidataDynamicObject noEdition = typed("Q4-x", "no edition", "Nomination");
        noEdition.put("year", new FlexibleDate(1970, 2, 1));  // day precision, no edition to source from

        new TransformEngine().applyYearFromDate(
                List.of(noEdition), "Nomination", "edition", "date", "year");

        assertEquals(new FlexibleDate(1970), noEdition.get("year"));
        assertEquals(FlexibleDate.Precision.YEAR,
                ((FlexibleDate) noEdition.get("year")).precision());
    }
}
