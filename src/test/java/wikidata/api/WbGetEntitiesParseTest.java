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

        assertFalse(out.containsKey("Q33"), "missing entity is dropped");
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
}
