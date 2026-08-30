package wikidata.explore.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.EntityKindRule;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The point of banking a property closure is the fetch that DOESN'T happen afterwards.
 *
 * <p>Asserting only which properties a request named would leave the payoff untested:
 * the closure is worth nothing unless the retained slice then answers the loads that
 * follow classification, and that depends on the store keeping what was banked, merging
 * slices, and the required properties being carried down to the lookup. Any one of those
 * drifting turns the optimisation off silently — the run stays correct and simply fetches
 * everything twice again.
 *
 * <p>This drives the REAL client and fact store, stubbing only the HTTP call, so what is
 * counted here is what the network would see.
 */
class BankedPropertyClosureTest {

    /** Counts physical fetches; answers every entity with the whole document, as
     *  wbgetentities does — the request never narrows by property. */
    private static final class CountingTransport extends WikidataApiClient {
        private final ObjectMapper json = new ObjectMapper();
        int fetches;
        final List<List<String>> fetchedQids = new ArrayList<>();

        CountingTransport() { super(WikidataApiClient.DEFAULT_USER_AGENT); }

        @Override public java.util.Map<String, List<String>> getAliases(
                List<String> qids, BatchLog batchLog) {
            java.util.Map<String, List<String>> result = new java.util.LinkedHashMap<>();
            qids.forEach(qid -> result.put(qid, List.of()));
            return result;
        }

        @Override protected JsonNode getEntitiesBatch(
                List<String> qids, boolean withClaims) throws Exception {
            fetches++;
            fetchedQids.add(List.copyOf(qids));
            StringBuilder entities = new StringBuilder();
            for (String qid : qids) {
                if (!entities.isEmpty()) entities.append(',');
                entities.append("\"").append(qid).append("\":{\"id\":\"").append(qid)
                        .append("\",\"labels\":{\"en\":{\"language\":\"en\",\"value\":\"")
                        .append(qid).append("\"}},\"aliases\":{},\"claims\":")
                        .append("""
                                {"P31":[{"mainsnak":{"snaktype":"value","property":"P31",
                                  "datavalue":{"type":"wikibase-entityid",
                                  "value":{"id":"Q5"}}},"rank":"normal"}],
                                 "P569":[{"mainsnak":{"snaktype":"value","property":"P569",
                                  "datavalue":{"type":"time",
                                  "value":{"time":"+1952-01-01T00:00:00Z"}}},"rank":"normal"}],
                                 "P734":[{"mainsnak":{"snaktype":"value","property":"P734",
                                  "datavalue":{"type":"wikibase-entityid",
                                  "value":{"id":"Q351735"}}},"rank":"normal"}]}""")
                        .append('}');
            }
            return json.readTree("{\"entities\":{" + entities + "}}");
        }
    }

    @Test void aBankedClosureSparesTheFetchAfterClassification() {
        GeneratedProjectModel model = model(true);
        CountingTransport api = new CountingTransport();

        WikidataDynamicObject nominee = new WikidataDynamicObject("Q42", "Adams");
        nominee.type("Nominee");
        nominee.assignClass("Nominee");
        WikidataDynamicObject nomination = new WikidataDynamicObject(
                "Q42$nomination", "Nomination");
        nomination.type("Nomination");
        nomination.put("nominee", nominee);
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(nomination, nominee));

        // The role's own declaration. Its response banks P569 and P734 as well.
        ReferentFieldLoad.apply(model, pool, api, null);
        int afterRoleLoad = api.fetches;
        assertTrue(afterRoleLoad > 0, "the role's evidence had to be fetched once");

        // The kind is settled, and its declarations become reachable.
        nominee.assignClass("Person");
        nominee.type("Person");
        nominee.typeKey("Person");
        nominee.removeClass("Nominee");
        OwnedComponents.Result parts = OwnedComponents.apply(model, pool, null, null);
        parts.addTo(pool);

        ReferentFieldLoad.apply(model, pool, api, null);

        assertEquals(afterRoleLoad, api.fetches,
                "Person.birthDate and Name.familyName were already in the retained slice");
        assertEquals("1952", String.valueOf(nominee.get("birthDate")).substring(0, 4),
                "and the values really were assigned from it");
    }

    @Test void ruleOnlyRemoteEvidenceBanksTheSameDownstreamClosure() {
        GeneratedProjectModel model = model(false); // no exposed Nominee.type field
        CountingTransport api = new CountingTransport();
        WikidataDynamicObject nominee = new WikidataDynamicObject("Q42", "Adams");
        nominee.type("Nominee");
        nominee.assignClass("Nominee");
        WikidataDynamicObject nomination = new WikidataDynamicObject(
                "Q42$nomination", "Nomination");
        nomination.type("Nomination");
        nomination.put("nominee", nominee);
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(nomination, nominee));

        ReferentKindClassifier.Result classified = ReferentKindClassifier.apply(
                model, pool, api, null);
        assertEquals(1, classified.classified());
        int afterEvidence = api.fetches;
        assertTrue(afterEvidence > 0, "rule-only P31 evidence was fetched once");

        OwnedComponents.Result parts = OwnedComponents.apply(model, pool, null, null);
        parts.addTo(pool);
        ReferentFieldLoad.load(model, pool, api, null, List.of(), true);

        assertEquals(afterEvidence, api.fetches,
                "remote classification banked Person and owned Name properties too");
        assertEquals("1952", String.valueOf(nominee.get("birthDate")).substring(0, 4));
    }

    private static GeneratedProjectModel model(boolean exposeEvidenceField) {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new StatementClassSource("P1411"));
        nomination.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Nominee");
        model.rootClass(nomination);

        GeneratedClassModel nominee = new GeneratedClassModel("Nominee");
        if (exposeEvidenceField) {
            nominee.addField("type", FieldType.ENTITY, FieldCardinality.COLLECTION)
                    .mapping().propertyPid("P31");
        }
        model.addClass(nominee);

        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.addField("birthDate", FieldType.DATE, FieldCardinality.SINGLE)
                .mapping().propertyPid("P569");
        GeneratedFieldModel name = person.addField(
                "name", FieldType.ENTITY, FieldCardinality.SINGLE);
        name.entityClassName("Name");
        name.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        model.addClass(person);

        GeneratedClassModel nameClass = new GeneratedClassModel("Name");
        nameClass.ownedClass(true);
        nameClass.addField("familyName", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().propertyPid("P734");
        model.addClass(nameClass);

        model.addEntityKindRule(new EntityKindRule("Person", List.of("Q5")));
        return model;
    }
}
