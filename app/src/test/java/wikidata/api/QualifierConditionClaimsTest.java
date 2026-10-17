package wikidata.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import wikidata.QualifierCondition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The wbgetentities claims answer a qualifier condition the way the query does: every
 * non-deprecated claim is kept with its rank, and the condition is applied before the
 * truthy rule (#395). What a field without a condition reads is unchanged.
 */
class QualifierConditionClaimsTest {

    private static final QualifierCondition V_BAND =
            new QualifierCondition("P1227", List.of("Q4892529"));

    private static String magnitude(String amount, String band, String rank) {
        return """
            { "rank": "%s",
              "mainsnak": { "snaktype": "value", "property": "P1215",
                "datavalue": { "type": "quantity",
                  "value": { "amount": "+%s", "unit": "1" } } },
              "qualifiers": { "P1227": [ { "snaktype": "value", "property": "P1227",
                "datavalue": { "type": "wikibase-entityid",
                  "value": { "entity-type": "item", "id": "%s" } } } ] } }
            """.formatted(rank, amount, band);
    }

    private static final String JSON = """
        { "entities": {
            "Q91017956": { "id": "Q91017956", "labels": {}, "claims": { "P1215": [ %s, %s ] } },
            "Q77155": { "id": "Q77155", "labels": {}, "claims": { "P1215": [ %s, %s ] } },
            "Q1": { "id": "Q1", "labels": {}, "claims": { "P1215": [ %s, %s, %s ] } }
        } }
        """.formatted(
            magnitude("2.93", "Q2520419", "normal"), magnitude("9.16", "Q66659648", "normal"),
            magnitude("1.45", "Q2520419", "normal"), magnitude("3.58", "Q4892529", "preferred"),
            magnitude("1.0", "Q2520419", "preferred"), magnitude("4.2", "Q4892529", "normal"),
            magnitude("0.5", "Q4892529", "deprecated"));

    private static Map<String, WikidataApiClient.ApiEntity> parse() throws Exception {
        Map<String, WikidataApiClient.ApiEntity> out = new LinkedHashMap<>();
        WikidataApiClient.parseEntities(new ObjectMapper().readTree(JSON), List.of("P1215"), out);
        return out;
    }

    @Test void theConditionSelectsTheVisualBandBeforeTheTruthyRule() throws Exception {
        Map<String, WikidataApiClient.ApiEntity> entities = parse();
        assertEquals(List.of(), entities.get("Q91017956").valuesWhere("P1215", V_BAND),
                "no V-band statement, so no magnitude");
        assertEquals(List.of("3.58"), entities.get("Q77155").valuesWhere("P1215", V_BAND));
        assertEquals(List.of("4.2"), entities.get("Q1").valuesWhere("P1215", V_BAND),
                "a preferred K band must not hide the normal V band; deprecated never counts");
    }

    @Test void withoutAConditionTheTruthyValuesAreUnchanged() throws Exception {
        Map<String, WikidataApiClient.ApiEntity> entities = parse();
        assertEquals(List.of("2.93", "9.16"), entities.get("Q91017956").values("P1215"));
        assertEquals(List.of("3.58"), entities.get("Q77155").values("P1215"));
        assertEquals(List.of("1.0"), entities.get("Q1").values("P1215"));
        assertEquals(entities.get("Q1").values("P1215"),
                entities.get("Q1").valuesWhere("P1215", null));
    }

    @Test void rankedStatementsKeepRankAndQualifiersForTheReaderToSelect() throws Exception {
        Map<String, List<WikidataApiClient.ApiStatement>> byQid = new LinkedHashMap<>();
        WikidataApiClient.parseStatements(new ObjectMapper().readTree(JSON), "P1215",
                WikidataApiClient.StatementDetail.RANKED, byQid);
        List<WikidataApiClient.ApiStatement> q1 = byQid.get("Q1");
        assertEquals(2, q1.size(), "deprecated is dropped, both ranks are kept");
        assertEquals(List.of("4.2"), QualifierCondition.select(q1, V_BAND,
                        WikidataApiClient.ApiStatement::rank,
                        WikidataApiClient.ApiStatement::qualifiers)
                .stream().map(WikidataApiClient.ApiStatement::value).toList());
    }
}
