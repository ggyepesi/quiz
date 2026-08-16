package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enrich must not re-stamp entities it merely walked past. Stamping the whole pool runs
 * the referent rule over the records that REFERENCE these entities, so a person reached
 * through Nomination.nominee took the ROLE class as its carrier type — and since the
 * mapper maps by that type, every Person disappeared from the domain while still
 * claiming Person among its classes.
 */
class EnrichKeepsKindTest {

    @Test void anEntityReachedThroughARoleFieldKeepsItsKind() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("oscars");
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.instanceMapping().propertyPid("P1411");
        nomination.statementSource(new StatementClassSource("OscarBackbone", "P1411"));
        GeneratedClassModel backbone = new GeneratedClassModel("OscarBackbone");
        backbone.instanceMapping().sourceQid("Q19020");
        GeneratedFieldModel nominee = nomination.addField(
                "nominee", FieldType.ENTITY, FieldCardinality.SINGLE);
        nominee.entityClassName("Nominee");
        GeneratedClassModel nomineeClass = new GeneratedClassModel("Nominee");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedFieldModel site = person.addField(
                "birthName", FieldType.ENTITY, FieldCardinality.SINGLE);
        site.entityClassName("BirthName");
        site.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedFieldModel associate = person.addField(
                "associate", FieldType.ENTITY, FieldCardinality.SINGLE);
        associate.entityClassName("Nominee");
        GeneratedClassModel birthName = new GeneratedClassModel("BirthName");
        birthName.ownedClass(true);
        birthName.addField("familyName", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .mapping().propertyPid("P734");
        project.rootClass(nomination);
        project.addClass(backbone);
        project.addClass(nomineeClass);
        project.addClass(person);
        project.addClass(birthName);
        project.addEntityKindRule(new EntityKindRule("Person", List.of("Q5")));

        WikidataDynamicObject kazan = new WikidataDynamicObject("Q72717", "Elia Kazan");
        kazan.type("Person");
        kazan.typeKey("Person");
        kazan.assignClass("Person");
        WikidataDynamicObject scorsese = new WikidataDynamicObject(
                "Q41148", "Martin Scorsese");
        scorsese.type("Person");
        scorsese.typeKey("Person");
        scorsese.assignClass("Person");
        // Person is itself touched by Enrich (BirthName is materialized/loaded). Its
        // role-typed field must not put Nominee back onto this classified referent.
        kazan.put("associate", scorsese);
        WikidataDynamicObject record = new WikidataDynamicObject("N1", "Elia Kazan");
        record.type("Nomination");
        record.typeKey("Nomination");
        record.assignClass("Nomination");
        record.put("nominee", kazan);

        GenerationRun previous = new GenerationRun(project, 1, null,
                new ArrayList<>(List.of(record, kazan, scorsese)), null, List.of());

        new GenerationPipeline().enrich(previous, project, api(), null);

        // The ROLE must not be added by a pass that merely walked past this entity.
        // In memory the carrier type is untouched either way — the damage shows on SAVE,
        // where the persisted type is recomputed as the most specific class and a tie
        // between Person and Nominee is broken ALPHABETICALLY. The entity then reloads
        // as a Nominee, the mapper maps by that, and every Person leaves the domain
        // while still claiming Person among its classes.
        assertEquals(java.util.Set.of("Person"), kazan.directClassNames(),
                "enrich stamps what it touched, not what references it");
        assertTrue(kazan.directClassNames().contains("Person"));
        assertEquals(java.util.Set.of("Person"), scorsese.directClassNames(),
                "a touched evidence-kind owner cannot re-stamp its classified referent");
    }

    private static WikidataApiClient api() {
        return new WikidataApiClient(WikidataApiClient.DEFAULT_USER_AGENT) {
            @Override public Map<String, ApiEntity> getEntities(
                    List<String> qids, List<String> pids, BatchLog log) {
                return Map.of();
            }
            @Override public PartialEntities getEntitiesBestEffort(
                    List<String> qids, List<String> pids, BatchLog log) {
                return new PartialEntities(Map.of(), 0);
            }
            @Override public PartialEntities getEntityClaimsPartial(
                    List<String> qids, List<String> pids, BatchLog log) {
                return new PartialEntities(Map.of(), 0);
            }
        };
    }
}
