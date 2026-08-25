package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reign is a statement about a person holding a position, and when it began is a
 * qualifier on that statement. YEAR was enough for a ceremony; a reign beginning on
 * a named day in the Julian calendar keeps neither its day nor its calendar under it.
 */
class QualifierDateKindTest {

    // Stephen I of Hungary, crowned 25 December 1000 — Julian, as Wikidata states it.
    private static final String CROWNED =
            "+1000-12-25T00:00:00Z" + aux.FlexibleDate.calendarMark(
                    "http://www.wikidata.org/entity/Q1985786");

    private static WikidataDynamicObject person(String qid, String name) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, name);
        o.type("Ruler");
        return o;
    }

    private static final class StubApi extends WikidataApiClient {
        private final Map<String, List<ApiStatement>> statements;
        StubApi(Map<String, List<ApiStatement>> statements) {
            super("test");
            this.statements = statements;
        }
        @Override public Map<String, List<ApiStatement>> getStatements(
                List<String> entityQids, String statementPid,
                List<String> qualifierPids, BatchLog log) {
            Map<String, List<ApiStatement>> out = new LinkedHashMap<>();
            for (String q : entityQids) {
                if (statements.containsKey(q)) out.put(q, statements.get(q));
            }
            return out;
        }
        @Override public Map<String, ApiEntity> getEntities(
                List<String> qids, List<String> claimPids, BatchLog log) {
            Map<String, ApiEntity> out = new LinkedHashMap<>();
            for (String q : qids) out.put(q, new ApiEntity(q, "", Map.of()));
            return out;
        }
    }

    private static WikidataDynamicObject reignOf(QualifierLoadConfig.Kind kind) {
        WikidataDynamicObject stephen = person("Q170206", "Stephen I");
        WikidataDynamicObject position = person("Q6412254", "Apostolic King of Hungary");
        StubApi api = new StubApi(Map.of("Q170206", List.of(
                new WikidataApiClient.ApiStatement("Q170206$r", "Q6412254",
                        Map.of("P580", List.of(CROWNED))))));

        QualifierLoadConfig cfg = new QualifierLoadConfig(
                "Ruler", "P39", "__Reign", "Reign", "position", "",
                List.of(new QualifierLoadConfig.Qualifier("P580", "reignStart", kind)),
                List.of("Q6412254"));

        return new QualifierLoader().api(api)
                .enrich(List.of(stephen, position), cfg, null, null).get(0);
    }

    @Test void aDateQualifierKeepsTheDayItStates() {
        aux.FlexibleDate start =
                assertInstanceOf(aux.FlexibleDate.class, reignOf(
                        QualifierLoadConfig.Kind.DATE).get("reignStart"));

        assertEquals(1000, start.getYear());
        assertEquals(12, start.getMonth());
        assertEquals(25, start.getDay());
        assertEquals(aux.FlexibleDate.Precision.DAY, start.precision());
    }

    @Test void aDateQualifierKeepsTheCalendarItWasStatedIn() {
        aux.FlexibleDate start = (aux.FlexibleDate) reignOf(
                QualifierLoadConfig.Kind.DATE).get("reignStart");

        assertEquals(aux.FlexibleDate.Calendar.JULIAN, start.calendar(),
                "the API attached the calendar model; only this parser reads it back");
        assertEquals("1000-12-25 (Julian)", start.format(),
                "and it survives the form the snapshot is written in");
    }

    @Test void theYearKindIsWhatItAlwaysWasAndKeepsNeither() {
        // Not a regression to fix — YEAR is a deliberate reduction, and a ceremony
        // needs nothing more. This pins the difference the two kinds exist for.
        aux.FlexibleDate start = (aux.FlexibleDate) reignOf(
                QualifierLoadConfig.Kind.YEAR).get("reignStart");

        assertEquals(1000, start.getYear());
        assertEquals(aux.FlexibleDate.Precision.YEAR, start.precision());
        assertEquals(aux.FlexibleDate.Calendar.GREGORIAN, start.calendar());
    }

    @Test void aMultiDateQualifierKeepsEveryUsableValue() {
        WikidataDynamicObject stephen = person("Q170206", "Stephen I");
        WikidataDynamicObject position = person("Q6412254", "King");
        String later = "+1001-01-01T00:00:00Z" + aux.FlexibleDate.precisionMark(11);
        StubApi api = new StubApi(Map.of("Q170206", List.of(
                new WikidataApiClient.ApiStatement("Q170206$r", "Q6412254",
                        Map.of("P580", List.of(CROWNED, later))))));
        QualifierLoadConfig cfg = new QualifierLoadConfig(
                "Ruler", "P39", "__Reign", "Reign", "position", "",
                List.of(new QualifierLoadConfig.Qualifier(
                        "P580", "reignStart", QualifierLoadConfig.Kind.DATE, true)),
                List.of("Q6412254"));

        Object value = new QualifierLoader().api(api)
                .enrich(List.of(stephen, position), cfg, null, null)
                .get(0).get("reignStart");
        assertInstanceOf(java.util.Collection.class, value);
        assertEquals(2, ((java.util.Collection<?>) value).size());
        assertTrue(((java.util.Collection<?>) value).stream()
                .allMatch(aux.FlexibleDate.class::isInstance));
    }
}
