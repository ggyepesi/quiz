package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.EntityKindRule;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Declaring a field on an owned class is ADDITIVE — no membership changes, nothing
 * already downloaded is invalidated — so it must not cost a re-extraction. Enrich
 * materializes the component and fetches only that property, for the QIDs the pool
 * already holds; the only thing it needs from the network is wbgetentities.
 */
class EnrichRunTest {

    @Test void loadsADeclaredComponentFieldOverTheExistingPool() throws Exception {
        GeneratedProjectModel project = project();
        WikidataDynamicObject person = new WikidataDynamicObject("Q42", "Douglas Adams");
        person.type("Person");
        person.typeKey("Person");
        GenerationRun previous = new GenerationRun(
                project, 1, null, new ArrayList<>(List.of(person)), null, List.of());

        GenerationRun enriched = new GenerationPipeline()
                .enrich(previous, project, api(), null);

        WikidataDynamicObject component = enriched.dynamicObjects().stream()
                .filter(o -> "Name".equals(o.typeName())).findFirst().orElse(null);
        assertNotNull(component, "the component is materialized: "
                + enriched.dynamicObjects().stream()
                        .map(WikidataDynamicObject::typeName).toList());
        assertEquals("Q42", component.getIdentifier(), "it carries the owner's identity");

        WikidataDynamicObject familyName =
                (WikidataDynamicObject) component.get("familyName");
        assertNotNull(familyName, "the declared property was fetched for the owner's qid");
        assertEquals("Q351735", familyName.getIdentifier());
        assertEquals("Adams", familyName.getDisplayName());

        // IN PLACE: the pool's own objects are enriched, not copies of them. Enrich
        // only adds, so copying tens of thousands of objects to touch a few thousand
        // would be the wrong price — the component hangs off the very object that was
        // already downloaded.
        assertEquals(component, person.get("fullname"),
                "the downloaded object itself carries the new component");
        assertNull(enriched.remapState(),
                "the stale pre-reify cache is dropped rather than re-transformed later");
    }

    /** A long run must SAY what it is doing: compiling and copying tens of thousands
     *  of objects happen before the first fetch, and in silence the run looks hung. */
    @Test void reportsEachPhaseBeforeItRuns() throws Exception {
        GeneratedProjectModel project = project();
        WikidataDynamicObject person = new WikidataDynamicObject("Q42", "Douglas Adams");
        person.type("Person");
        person.typeKey("Person");
        GenerationRun previous = new GenerationRun(
                project, 1, null, new ArrayList<>(List.of(person)), null, List.of());
        StringBuilder log = new StringBuilder();

        new GenerationPipeline().enrich(previous, project, api(),
                wikidata.explore.extract.GenerationLog.of(log::append));

        String reported = log.toString();
        assertTrue(reported.contains("compiling"), reported);
        assertTrue(reported.contains("1 downloaded object"), reported);
        // Names the declarations it will try, so a run that fetches nothing says why.
        assertTrue(reported.contains("Name.familyName (P734)"), reported);
    }

    /** Answers wbgetentities: Q42 has P734 → Q351735, which is labelled "Adams". */
    private static WikidataApiClient api() {
        return new WikidataApiClient(WikidataApiClient.DEFAULT_USER_AGENT) {
            @Override public Map<String, ApiEntity> getEntities(
                    List<String> qids, List<String> claimPids, BatchLog batchLog) {
                Map<String, ApiEntity> out = new java.util.LinkedHashMap<>();
                for (String qid : qids) {
                    if ("Q42".equals(qid)) {
                        out.put(qid, new ApiEntity("Q42", "Douglas Adams",
                                Map.of("P734", List.of("Q351735")), false, Map.of()));
                    } else if ("Q351735".equals(qid)) {
                        out.put(qid, new ApiEntity("Q351735", "Adams",
                                Map.of(), false, Map.of()));
                    }
                }
                return out;
            }
        };
    }

    private static GeneratedProjectModel project() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("people");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.instanceMapping().propertyPid("P31");
        person.instanceMapping().sourceQid("Q5");
        GeneratedFieldModel fullname = person.addField(
                "fullname", FieldType.ENTITY, FieldCardinality.SINGLE);
        fullname.entityClassName("Name");
        fullname.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);

        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.ownedClass(true);
        GeneratedFieldModel familyName = name.addField(
                "familyName", FieldType.ENTITY, FieldCardinality.SINGLE);
        familyName.entityClassName("FamilyName");
        familyName.mapping().propertyPid("P734");

        project.rootClass(person);
        project.addClass(name);
        project.addClass(new GeneratedClassModel("FamilyName"));
        project.addEntityKindRule(new EntityKindRule("Person", List.of("Q5")));
        return project;
    }
}
