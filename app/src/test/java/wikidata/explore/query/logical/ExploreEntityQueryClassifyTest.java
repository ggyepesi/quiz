package wikidata.explore.query.logical;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Kind column: a relation's sample value must classify to entity / image / number /
 *  date / text, and the example should read as a name (not a bare QID) or a file name. */
class ExploreEntityQueryClassifyTest {

    private static String kind(String raw, String label) {
        return ExploreEntityQuery.classify(raw, label)[0];
    }

    private static String example(String raw, String label) {
        return ExploreEntityQuery.classify(raw, label)[1];
    }

    @Test
    void entityValueUsesItsLabelNotTheBareQid() {
        assertEquals("entity", kind("http://www.wikidata.org/entity/Q30", "United States"));
        assertEquals("United States",
                example("http://www.wikidata.org/entity/Q30", "United States"));
    }

    @Test
    void entityWithoutALabelFallsBackToTheQid() {
        assertEquals("Q30", example("http://www.wikidata.org/entity/Q30", null));
    }

    @Test
    void commonsMediaIsAnImageShownByFileName() {
        assertEquals("image", kind(
                "http://commons.wikimedia.org/wiki/Special:FilePath/Flag%20of%20Spain.svg",
                null));
        assertEquals("Flag of Spain.svg", example(
                "http://commons.wikimedia.org/wiki/Special:FilePath/Flag%20of%20Spain.svg",
                null));
    }

    @Test
    void quantityLiteralIsANumber() {
        assertEquals("number", kind("47000000", null));
        assertEquals("number", kind("8.5", null));
    }

    @Test
    void timeLiteralIsADate() {
        assertEquals("date", kind("2020-01-01T00:00:00Z", null));
        assertEquals("date", kind("-0044-03-15", null));
    }

    @Test
    void plainStringIsText() {
        assertEquals("text", kind("Kingdom of Spain", null));
    }

    @Test
    void blankValueIsMarkedEmpty() {
        assertEquals("—", kind("", null));
        assertEquals("—", kind(null, null));
    }
}
