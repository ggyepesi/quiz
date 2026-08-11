package wikidata.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing a wbgetentities response into labels + entity-QID claim lists (no network). */
class WbGetEntitiesParseTest {

    private static final String JSON = """
        {
          "entities": {
            "Q11": {
              "id": "Q11",
              "labels": { "en": { "language": "en", "value": "First nominee" } },
              "claims": {
                "P31": [
                  { "rank": "normal",
                    "mainsnak": { "datavalue": { "type": "wikibase-entityid",
                                                 "value": { "id": "Q5" } } } },
                  { "rank": "preferred",
                    "mainsnak": { "datavalue": { "type": "wikibase-entityid",
                                                 "value": { "numeric-id": 215627 } } } },
                  { "rank": "deprecated",
                    "mainsnak": { "datavalue": { "value": { "id": "Q999" } } } }
                ]
              }
            },
            "Q22": {
              "id": "Q22",
              "labels": { "en": { "language": "en", "value": "Second nominee" } },
              "claims": {}
            },
            "Q33": { "missing": "" }
          }
        }
        """;

    private static Map<String, WikidataApiClient.ApiEntity> parse(List<String> pids)
            throws Exception {
        Map<String, WikidataApiClient.ApiEntity> out = new LinkedHashMap<>();
        WikidataApiClient.parseEntities(
                new ObjectMapper().readTree(JSON), pids, out);
        return out;
    }

    @Test
    void extractsLabelsAndClaimEntityQidsSkippingDeprecatedAndMissing() throws Exception {
        Map<String, WikidataApiClient.ApiEntity> out = parse(List.of("P31"));

        assertTrue(out.get("Q33").missing(), "explicit missing entity is retained as evidence");
        assertEquals("First nominee", out.get("Q11").label());

        // both id and numeric-id forms resolve; deprecated is skipped, in order.
        assertEquals(List.of("Q5", "Q215627"), out.get("Q11").claim("P31"));

        // an entity with no P31 has an empty claim list.
        assertTrue(out.get("Q22").claim("P31").isEmpty());
    }

    @Test
    void withoutRequestedPidsOnlyLabelsAreParsed() throws Exception {
        Map<String, WikidataApiClient.ApiEntity> out = parse(List.of());
        assertEquals("First nominee", out.get("Q11").label());
        assertTrue(out.get("Q11").claim("P31").isEmpty(),
                "no PIDs requested → no claims extracted");
    }

    // A nominee (Q11) with two P1411 nomination statements, each carrying an entity
    // qualifier (P805 edition) and, on the first, a repeated entity qualifier (P2453
    // co-nominee) and a time qualifier (P585). Deprecated statements are skipped.
    private static final String STATEMENTS_JSON = """
        {
          "entities": {
            "Q11": {
              "id": "Q11",
              "claims": {
                "P1411": [
                  { "rank": "normal",
                    "id": "Q11$aaa",
                    "mainsnak": { "datavalue": { "type": "wikibase-entityid",
                                                 "value": { "id": "Q102427" } } },
                    "qualifiers": {
                      "P805":  [ { "datavalue": { "type": "wikibase-entityid",
                                                  "value": { "id": "Q1968" } } } ],
                      "P2453": [ { "datavalue": { "type": "wikibase-entityid",
                                                  "value": { "id": "Q30" } } },
                                 { "datavalue": { "type": "wikibase-entityid",
                                                  "value": { "id": "Q40" } } } ],
                      "P585":  [ { "datavalue": { "type": "time",
                                                  "value": { "time": "+1968-04-10T00:00:00Z" } } } ]
                    }
                  },
                  { "rank": "normal",
                    "id": "Q11$bbb",
                    "mainsnak": { "datavalue": { "type": "wikibase-entityid",
                                                 "value": { "numeric-id": 281939 } } } },
                  { "rank": "deprecated",
                    "id": "Q11$ccc",
                    "mainsnak": { "datavalue": { "type": "wikibase-entityid",
                                                 "value": { "id": "Q999" } } } }
                ]
              }
            },
            "Q22": { "id": "Q22", "claims": {} }
          }
        }
        """;

    @Test
    void parsesStatementsWithEntityTimeAndRepeatedQualifiers() throws Exception {
        Map<String, List<WikidataApiClient.ApiStatement>> out = new LinkedHashMap<>();
        WikidataApiClient.parseStatements(
                new ObjectMapper().readTree(STATEMENTS_JSON),
                "P1411", List.of("P805", "P2453", "P585"), out);

        assertFalse(out.containsKey("Q22"), "no P1411 statements → entity dropped");
        List<WikidataApiClient.ApiStatement> stmts = out.get("Q11");
        assertEquals(2, stmts.size(), "deprecated statement skipped");

        WikidataApiClient.ApiStatement s0 = stmts.get(0);
        assertEquals("Q11$aaa", s0.id());
        assertEquals("Q102427", s0.value());                       // mainsnak category
        assertEquals(List.of("Q1968"), s0.qualifier("P805"));      // entity qualifier
        assertEquals(List.of("Q30", "Q40"), s0.qualifier("P2453")); // repeated qualifier
        assertEquals(List.of("+1968-04-10T00:00:00Z"), s0.qualifier("P585")); // time

        // second statement: numeric-id form, no qualifiers requested-present
        assertEquals("Q281939", stmts.get(1).value());
        assertTrue(stmts.get(1).qualifier("P805").isEmpty());
    }
}
