package wikidata.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing a wbgetentities response into labels + entity-QID claim lists (no network). */
class WbGetEntitiesParseTest {

    private static String entityClaim(String qid, String languageQid) {
        String qualifier = languageQid == null ? "" : """
                , "qualifiers": { "P407": [
                    { "datavalue": { "type": "wikibase-entityid",
                                      "value": { "id": "%s" } } }
                  ] }
                """.formatted(languageQid);
        return """
                { "rank": "normal",
                  "mainsnak": { "datavalue": { "type": "wikibase-entityid",
                                                 "value": { "id": "%s" } } }%s }
                """.formatted(qid, qualifier);
    }

    private static final String JSON = """
        {
          "entities": {
            "Q11": {
              "id": "Q11",
              "labels": { "en": { "language": "en", "value": "First nominee" } },
              "aliases": { "en": [
                { "language": "en", "value": "Nominee one" },
                { "language": "en", "value": "First person" }
              ] },
              "claims": {
                "P577": [
                  { "rank": "normal",
                    "mainsnak": { "datavalue": { "type": "time",
                                                   "value": { "time": "+1974-06-20T00:00:00Z" } } } }
                ],
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
              "labels": { "mul": { "language": "mul", "value": "Second nominee" } },
              "aliases": { },
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
    void extractsLiteralAndEntityValuesFromTheSameClaimsDocument() throws Exception {
        WikidataApiClient.ApiEntity entity = parse(List.of("P31", "P577")).get("Q11");

        // Q11 marks one P31 preferred, so that is the truthy value — as wdt:P31 gives.
        assertEquals(List.of("Q215627"), entity.values("P31"));
        assertEquals(List.of("+1974-06-20T00:00:00Z"), entity.values("P577"));
        assertTrue(entity.entityQids("P577").isEmpty(),
                "the entity-only accessor remains honest for literal properties");
    }

    @Test
    void extractsLabelsAndClaimEntityQidsSkippingDeprecatedAndMissing() throws Exception {
        Map<String, WikidataApiClient.ApiEntity> out = parse(List.of("P31"));

        assertTrue(out.get("Q33").missing(), "explicit missing entity is retained as evidence");
        assertEquals("First nominee", out.get("Q11").label());
        assertEquals(List.of("Nominee one", "First person"), out.get("Q11").aliases());
        assertEquals("Second nominee", out.get("Q22").label(),
                "multilingual label is the fallback when English is absent");

        // both id and numeric-id forms resolve; deprecated is skipped, in order.
        assertEquals(List.of("Q215627"), out.get("Q11").entityQids("P31"),
                "the preferred statement outranks the normal one, deprecated is dropped");

        // an entity with no P31 has an empty claim list.
        assertTrue(out.get("Q22").entityQids("P31").isEmpty());
    }

    @Test
    void withoutRequestedPidsOnlyLabelsAreParsed() throws Exception {
        Map<String, WikidataApiClient.ApiEntity> out = parse(List.of());
        assertEquals("First nominee", out.get("Q11").label());
        assertTrue(out.get("Q11").entityQids("P31").isEmpty(),
                "no PIDs requested → no claims extracted");
    }

    @Test
    void languageQualifiedClaimsPreferTheFactoredDefaultLanguage() throws Exception {
        String json = """
                { "entities": { "Q1": { "id": "Q1", "claims": { "P735": [
                %s, %s, %s
                ] } } } }
                """.formatted(entityClaim("Q10", "Q1860"),
                entityClaim("Q20", "Q188"), entityClaim("Q30", null));
        Map<String, WikidataApiClient.ApiEntity> out = new LinkedHashMap<>();
        WikidataApiClient.parseEntities(
                new ObjectMapper().readTree(json), List.of("P735"), out);

        assertEquals(List.of("Q10"), out.get("Q1").entityQids("P735"));
    }

    // The branch that REMOVES data. When Wikidata states a language for every value
    // and none of them is ours, there is no honest answer: presenting a French given
    // name as the English one asserts something Wikidata denies. Empty is the result,
    // and it is pinned here because nothing else would fail if it stopped happening —
    // a field would simply go quiet.
    @Test
    void everyValueBeingForeignYieldsNothingRatherThanAWrongLanguage() throws Exception {
        String json = """
                { "entities": { "Q1": { "id": "Q1", "claims": { "P735": [
                %s, %s
                ] } } } }
                """.formatted(entityClaim("Q10", "Q188"), entityClaim("Q20", "Q150"));
        Map<String, WikidataApiClient.ApiEntity> out = new LinkedHashMap<>();
        WikidataApiClient.parseEntities(
                new ObjectMapper().readTree(json), List.of("P735"), out);

        assertEquals(List.of(), out.get("Q1").entityQids("P735"));
    }

    @Test
    void unqualifiedClaimsRemainWhenNoDefaultLanguageClaimExists() throws Exception {
        String json = """
                { "entities": { "Q1": { "id": "Q1", "claims": { "P735": [
                %s, %s, %s
                ] } } } }
                """.formatted(entityClaim("Q10", "Q188"),
                entityClaim("Q20", null), entityClaim("Q30", null));
        Map<String, WikidataApiClient.ApiEntity> out = new LinkedHashMap<>();
        WikidataApiClient.parseEntities(
                new ObjectMapper().readTree(json), List.of("P735"), out);

        assertEquals(List.of("Q20", "Q30"), out.get("Q1").entityQids("P735"));
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

    /**
     * Aliases ride the entity request, and the response — not an assumption about it —
     * says whether they were answered. A load banks "this entity's names are known" from
     * that bit, so a document fetched without aliases must report false: coverage
     * recorded for a fact never requested is a fact that is then never fetched again.
     */
    @Test
    void anEntityReportsWhetherItsAliasesWereAnswered() throws Exception {
        assertTrue(WikidataApiClient.entityProps(true).contains("aliases"),
                "the claims request asks for the identity metadata loads record as covered");
        assertTrue(WikidataApiClient.entityProps(false).contains("aliases"),
                "so does the labels-only request");

        Map<String, WikidataApiClient.ApiEntity> answered = new LinkedHashMap<>();
        WikidataApiClient.parseEntities(
                new ObjectMapper().readTree(JSON), List.of("P31"), answered);
        assertTrue(answered.get("Q11").aliasesAnswered());
        assertTrue(answered.get("Q22").aliasesAnswered(),
                "an entity WITH an aliases node but no values has been answered: it has none");
        assertEquals(List.of(), answered.get("Q22").aliases());

        Map<String, WikidataApiClient.ApiEntity> unanswered = new LinkedHashMap<>();
        WikidataApiClient.parseEntities(new ObjectMapper().readTree("""
                {"entities": {"Q11": {"id": "Q11",
                  "labels": {"en": {"language": "en", "value": "First nominee"}},
                  "claims": {}}}}"""), List.of("P31"), unanswered);
        assertFalse(unanswered.get("Q11").aliasesAnswered(),
                "no aliases node — nobody asked, and an empty list cannot say so");
    }

    @Test
    void entityRequestsCarryAndParseOnlyTheEnglishWikipediaSitelink() throws Exception {
        assertTrue(WikidataApiClient.entityProps(true).contains("sitelinks"));
        assertTrue(WikidataApiClient.entityProps(false).contains("sitelinks"));
        Map<String, WikidataApiClient.ApiEntity> parsed = new LinkedHashMap<>();
        WikidataApiClient.parseEntities(new ObjectMapper().readTree("""
                {"entities":{"Q157058":{"labels":{"en":{"value":"Blood Diamond"}},
                  "sitelinks":{"enwiki":{"title":"Blood Diamond"}}}}}
                """), List.of(), parsed);
        assertEquals("Blood Diamond", parsed.get("Q157058").enwikiTitle());
    }

    @Test void entityRequestProjectionOmitsUnneededMetadata() {
        assertEquals("claims", WikidataApiClient.entityProps(true, Set.of()));
        assertEquals("labels|claims", WikidataApiClient.entityProps(true,
                Set.of(FactDemand.EntityMetadata.LABEL)));
        assertEquals("sitelinks", WikidataApiClient.entityProps(false,
                Set.of(FactDemand.EntityMetadata.SITELINKS)));
    }

    @Test
    void aliasesReuseAClaimsDocumentRetainedByAnEarlierConsumer() throws Exception {
        WikidataApiClient api = new WikidataApiClient("test");
        api.facts().accept(new ObjectMapper().readTree(JSON), true, List.of("P31"));

        Map<String, List<String>> aliases = api.getAliases(List.of("Q11", "Q22"), null);

        assertEquals(List.of("Nominee one", "First person"), aliases.get("Q11"));
        assertEquals(List.of(), aliases.get("Q22"));
        assertEquals(2, api.facts().cacheHits(),
                "the retained response avoids a standalone alias request");
    }

    /**
     * A field takes the property's TRUTHY value — preferred if the entity says which
     * one is, otherwise every normal statement. A film states a release date per country
     * and marks the first preferred; reading all of them turned a single-valued field
     * into a dozen values the moment it moved off the SPARQL path onto this one. A
     * statement CLASS is the other case and keeps them all, which is why reification
     * does not read through here.
     */
    @Test void aFieldTakesThePreferredStatementWhenTheEntityRanksOne() throws Exception {
        String json = """
            {"entities": {"Q1": {"id": "Q1", "claims": {
              "P577": [
                {"rank": "normal", "mainsnak": {"datavalue":
                  {"type": "time", "value": {"time": "+1958-10-10T00:00:00Z"}}}},
                {"rank": "preferred", "mainsnak": {"datavalue":
                  {"type": "time", "value": {"time": "+1958-01-01T00:00:00Z"}}}},
                {"rank": "deprecated", "mainsnak": {"datavalue":
                  {"type": "time", "value": {"time": "+1900-01-01T00:00:00Z"}}}}],
              "P31": [
                {"rank": "normal", "mainsnak": {"datavalue":
                  {"type": "wikibase-entityid", "value": {"id": "Q5"}}}},
                {"rank": "normal", "mainsnak": {"datavalue":
                  {"type": "wikibase-entityid", "value": {"id": "Q215627"}}}}]}}}}""";

        Map<String, WikidataApiClient.ApiEntity> out = new LinkedHashMap<>();
        WikidataApiClient.parseEntities(
                new ObjectMapper().readTree(json), List.of("P577", "P31"), out);
        WikidataApiClient.ApiEntity entity = out.get("Q1");

        assertEquals(List.of("+1958-01-01T00:00:00Z"), entity.values("P577"),
                "the ranked statement is the one the field takes");
        assertEquals(List.of("Q5", "Q215627"), entity.entityQids("P31"),
                "with no preference stated, every normal statement is truthy");
    }

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

    @Test
    void statementConsumerReusesThePropertyRetainedWithSubjectDiscovery()
            throws Exception {
        WikidataApiClient api = new WikidataApiClient("test");
        api.facts().recordRetentionPlan(
                "subject discovery", List.of("Q11"), List.of("P1411"));
        api.facts().accept(
                new ObjectMapper().readTree(STATEMENTS_JSON), true, List.of("P1411"));
        api.facts().recordDemand(
                "statement acquisition", List.of("Q11"), List.of("P1411"));

        Map<String, List<WikidataApiClient.ApiStatement>> statements =
                api.getStatements(List.of("Q11"), "P1411",
                        List.of("P805", "P2453", "P585"), null);

        assertEquals(2, statements.get("Q11").size());
        assertEquals(1, api.facts().cacheHits());
        assertEquals(0, api.facts().lateDemandPairs(),
                "the later consumer was announced before subject acquisition");
    }
}
