package wikidata.explore.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import datasource.api.SourceRecipe;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A model must serialize to the same bytes every time it is written.
 *
 * <p>It did not. {@code SourceRecipe.parameters} is a {@code Map.copyOf}, whose iteration
 * order the JVM randomizes per process, so an untouched model produced a different file —
 * and a different SHA-256 — on each restart. {@code DomainSave.signature} is that hash, so
 * a domain could report its instances stale purely because the workbench had been
 * reopened. Four runs of one unchanged model gave three different signatures.
 *
 * <p>A single JVM shares one salt, so serializing twice here cannot reproduce that. What
 * is checked instead is the property that makes it impossible: every serialized map writes
 * its keys in order. Randomized order fails this as soon as any map is out of order, and a
 * model contains many.
 */
class ModelSerializationIsStableTest {

    private static GeneratedProjectModel modelWithRecipes() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("People");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.instanceMapping().sourceQid("Q5");
        person.instanceMapping().propertyPid("P31");
        person.addField("birthName", datasource.schema.FieldType.STRING,
                FieldCardinality.SINGLE);
        project.rootClass(person);
        // Enough keys that an accidentally sorted order is not a plausible pass, and an
        // insertion order deliberately different from the sorted one.
        LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
        parameters.put("sourceType", "SPARQL");
        parameters.put("property", "P1477");
        parameters.put("label", "birth name");
        parameters.put("valueLanguage", "");
        parameters.put("direction", "outgoing");
        person.sourceBindings().add(new datasource.api.SourceBinding(
                datasource.api.SourceBindingTarget.fieldValue("Person", "birthName",
                        datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new SourceRecipe("wikidata", "property-value", parameters)));
        GeneratedClassModel role = new GeneratedClassModel("Laureate");
        project.addClass(role);
        project.addEntityKindRule(new EntityKindRule("Person", List.of("Q5")));
        project.representationClasses(role, List.of("Person"));
        return project;
    }

    @Test void everySerializedMapWritesItsKeysInOrder() throws Exception {
        String json = new GeneratedProjectModelStore().toJson(modelWithRecipes());

        List<String> unordered = new ArrayList<>();
        checkMapsOrdered(new ObjectMapper().readTree(json), "", false, unordered);

        assertEquals(List.of(), unordered,
                "a map written out of order makes the file, and the signature, depend on "
                        + "which JVM wrote it");
    }

    @Test void thePropertyHoldsForTheRecipeThatExposedIt() throws Exception {
        String json = new GeneratedProjectModelStore().toJson(modelWithRecipes());

        int at = json.indexOf("\"property\" : \"P1477\"");
        assertTrue(at >= 0, "the recipe is in the file");
        String parameters = json.substring(json.lastIndexOf("\"parameters\"", at),
                json.indexOf('}', at));

        assertTrue(parameters.indexOf("\"direction\"") < parameters.indexOf("\"label\""),
                parameters);
        assertTrue(parameters.indexOf("\"label\"") < parameters.indexOf("\"property\""),
                parameters);
        assertTrue(parameters.indexOf("\"property\"") < parameters.indexOf("\"sourceType\""),
                parameters);
    }

    @Test void contextualRepresentationIsPersistedWithStableReferences() throws Exception {
        String json = new GeneratedProjectModelStore().toJson(modelWithRecipes());

        JsonNode rule = new ObjectMapper().readTree(json)
                .path("entityRepresentationRules").path(1).path(0);
        assertEquals("Laureate", rule.path("roleClassName").asText());
        assertFalse(rule.path("roleClassId").asText().isBlank());
        assertEquals("Person", rule.path("representationClassName").asText());
        assertFalse(rule.path("representationClassId").asText().isBlank());
    }

    /**
     * Records the path of every serialized MAP whose keys are not ascending. Only maps:
     * a record or bean writes its properties in declaration order, which is deliberate
     * and is not what randomizes between runs.
     */
    private static void checkMapsOrdered(
            JsonNode node, String path, boolean isMap, List<String> unordered) {
        if (node.isObject()) {
            if (isMap) {
                List<String> names = new ArrayList<>();
                node.fieldNames().forEachRemaining(names::add);
                // Jackson writes the type id first whatever the key order, and it is not
                // one of the map's own entries.
                names.remove("@class");
                List<String> sorted = new ArrayList<>(names);
                java.util.Collections.sort(sorted);
                if (!names.equals(sorted)) unordered.add(path + " " + names);
            }
            node.fields().forEachRemaining(entry -> checkMapsOrdered(entry.getValue(),
                    path + "/" + entry.getKey(),
                    "parameters".equals(entry.getKey()), unordered));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                checkMapsOrdered(node.get(i), path + "[" + i + "]", isMap, unordered);
            }
        }
    }
}
