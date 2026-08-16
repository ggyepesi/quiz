package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * An evidence kind is STAMPED from P31 evidence, never queried, so no root query ever
 * fetches its fields — the same position a referenced-only class is in. Declaring
 * {@code Person.birthDate (P569)} used to validate, discover, and then load nothing.
 */
class EvidenceKindFieldLoadTest {

    @Test void aFieldDeclaredOnAnEvidenceKindIsLoaded() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("oscars");
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        GeneratedFieldModel nominee = nomination.addField(
                "nominee", FieldType.ENTITY, FieldCardinality.SINGLE);
        nominee.entityClassName("Nominee");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedFieldModel birthDate = person.addField(
                "birthDate", FieldType.DATE, FieldCardinality.SINGLE);
        birthDate.mapping().propertyPid("P569");
        project.rootClass(nomination);
        project.addClass(person);
        project.addEntityKindRule(new EntityKindRule("Person", List.of("Q5")));

        assertEquals(MembershipPattern.EVIDENCE_KIND,
                MembershipPattern.of(person, project), "stamped, never queried");

        WikidataDynamicObject kazan = new WikidataDynamicObject("Q72717", "Elia Kazan");
        kazan.type("Person");
        kazan.typeKey("Person");
        kazan.assignClass("Person");

        int loaded = ReferentFieldLoad.apply(project, List.of(kazan), api(), null);

        assertEquals(1, loaded);
        assertNotNull(kazan.get("birthDate"), "the declared property reached the stamped kind");
        assertEquals("1909", String.valueOf(kazan.get("birthDate")).substring(0, 4));
    }

    private static WikidataApiClient api() {
        return new WikidataApiClient(WikidataApiClient.DEFAULT_USER_AGENT) {
            @Override public Map<String, Map<String, List<ApiStatement>>>
                    getStatementsByProperty(List<String> qids, List<String> pids,
                                            BatchLog log) {
                Map<String, Map<String, List<ApiStatement>>> out = new LinkedHashMap<>();
                if (qids.contains("Q72717") && pids.contains("P569")) {
                    out.put("P569", Map.of("Q72717", List.of(
                            new ApiStatement("Q72717$s1", "+1909-09-07T00:00:00Z", Map.of()))));
                }
                return out;
            }
        };
    }
}
