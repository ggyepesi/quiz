package wikidata.explore.transform;

import wikidata.explore.model.EntityBound;
import org.junit.jupiter.api.Test;
import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Reifying nominations from wbgetentities statements (the unified path — no SPARQL,
 * no recovery). A stub {@link WikidataApiClient} feeds canned statements+qualifiers.
 */
class QualifierLoaderReifyTest {

    private static WikidataDynamicObject obj(String qid, String name, String type) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, name);
        o.type(type);
        return o;
    }

    private static WikidataApiClient.ApiStatement stmt(
            String id, String value, Map<String, List<String>> quals) {
        return new WikidataApiClient.ApiStatement(id, value, quals);
    }

    /** Serves canned statements + labels; overrides only the two calls enrich uses. */
    private static final class StubApi extends WikidataApiClient {
        final Map<String, List<ApiStatement>> statements;
        final Map<String, String> labels;

        StubApi(Map<String, List<ApiStatement>> statements, Map<String, String> labels) {
            super("test");
            this.statements = statements;
            this.labels = labels;
        }

        @Override
        public Map<String, List<ApiStatement>> getStatements(
                List<String> entityQids, String statementPid,
                List<String> qualifierPids, BatchLog log) {
            Map<String, List<ApiStatement>> out = new LinkedHashMap<>();
            for (String q : entityQids) {
                if (statements.containsKey(q)) out.put(q, statements.get(q));
            }
            return out;
        }

        @Override
        public Map<String, ApiEntity> getEntities(
                List<String> qids, List<String> claimPids, BatchLog log) {
            Map<String, ApiEntity> out = new LinkedHashMap<>();
            for (String q : qids) {
                out.put(q, new ApiEntity(q, labels.getOrDefault(q, ""), Map.of()));
            }
            return out;
        }
    }

    @Test
    void reifiesOneNominationPerAllowedStatementWithQualifiers() {
        WikidataDynamicObject nominee = obj("Q11", "Denzel", "OscarNominations");
        // A pooled, already-labelled category and co-nominee (unified by qid).
        WikidataDynamicObject category = obj("Q102427", "Best Actor", "Category");
        WikidataDynamicObject pooledCo = obj("Q30", "Pooled Co-nominee", "OscarNominations");

        Map<String, List<WikidataApiClient.ApiStatement>> statements = Map.of(
                "Q11", List.of(
                        stmt("Q11$a", "Q102427", Map.of(
                                "P585", List.of("+1968-04-10T00:00:00Z"),   // YEAR
                                "P2453", List.of("Q30", "Q40"))),           // multi ENTITY
                        stmt("Q11$b", "Q999", Map.of())));                  // NOT an allowed value

        StubApi api = new StubApi(statements, Map.of("Q40", "Fresh Co-nominee"));

        QualifierLoadConfig cfg = new QualifierLoadConfig(
                "OscarNominations",
                "P1411",
                "__Nomination",
                "Nomination",
                "category",
                EntityBound.explicit(List.of("Q102427")),
                List.of(
                        new QualifierLoadConfig.Qualifier("P585", "year",
                                QualifierLoadConfig.Kind.YEAR),
                        new QualifierLoadConfig.Qualifier("P2453", "coNominees",
                                QualifierLoadConfig.Kind.ENTITY, true)));   // allowed values: only Best Actor

        List<WikidataDynamicObject> created = new QualifierLoader().api(api)
                .enrich(List.of(nominee, category, pooledCo), cfg, null, null);

        // Only the allowed-value statement reifies; Q999 is dropped.
        assertEquals(1, created.size());
        WikidataDynamicObject nom = created.get(0);

        // value ref reuses the pooled, labelled category object.
        assertSame(category, nom.get("category"));

        // YEAR qualifier → FlexibleDate parsed from the ISO time.
        assertInstanceOf(aux.FlexibleDate.class, nom.get("year"));
        assertEquals(1968, ((aux.FlexibleDate) nom.get("year")).getYear());

        // multi ENTITY qualifier: both co-nominees, pooled one unified, fresh one labelled.
        List<?> co = (List<?>) nom.get("coNominees");
        assertEquals(2, co.size());
        assertSame(pooledCo, co.get(0));                                  // unified by qid
        assertEquals("Fresh Co-nominee",
                ((WikidataDynamicObject) co.get(1)).getDisplayName());    // label-resolved

        // The nomination is named after (and attached to) its value + entity.
        assertEquals("Best Actor", nom.getDisplayName());
        assertSame(nom, nominee.get("__Nomination"));
    }

    @Test
    void anEntityWithNoStatementsGetsNoNomination() {
        WikidataDynamicObject nominee = obj("Q11", "Nobody", "OscarNominations");
        StubApi api = new StubApi(Map.of(), Map.of());
        QualifierLoadConfig cfg = new QualifierLoadConfig(
                "OscarNominations",
                "P1411",
                "__Nomination",
                "Nomination",
                "category",
                EntityBound.explicit(List.of("Q102427")),
                List.of());

        List<WikidataDynamicObject> created = new QualifierLoader().api(api)
                .enrich(List.of(nominee), cfg, null, null);

        assertEquals(0, created.size());
        assertNull(nominee.get("__Nomination"));
    }
}
