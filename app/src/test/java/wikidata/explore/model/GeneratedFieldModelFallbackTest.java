package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A field's optional fallback source persists through the store; fields without one keep a
 *  null fallback (so pre-existing snapshots stay byte-identical). */
class GeneratedFieldModelFallbackTest {

    @Test
    void fallbackMappingRoundTripsAndDefaultsToNull() throws Exception {
        GeneratedProjectModel project = GeneratedProjectModel.constellationDemo();

        GeneratedFieldModel withFallback = project.rootClass().addField(
                "population", FieldType.NUMBER, FieldCardinality.SINGLE);
        withFallback.mapping().sourceType(FieldSourceType.SPARQL);
        withFallback.mapping().propertyPid("P1082");
        withFallback.ensureFallbackMapping().sourceType(FieldSourceType.DBPEDIA);
        withFallback.fallbackMapping().propertyPid("populationTotal");

        GeneratedFieldModel noFallback = project.rootClass().addField(
                "area", FieldType.NUMBER, FieldCardinality.SINGLE);
        noFallback.mapping().sourceType(FieldSourceType.SPARQL);
        noFallback.mapping().propertyPid("P2046");

        File file = File.createTempFile("model-fallback", ".json");
        file.deleteOnExit();
        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        store.save(project, file);

        GeneratedProjectModel loaded = store.load(file);
        assertEquals(FieldSourceType.DBPEDIA,
                field(loaded, "population").fallbackMapping().sourceType());
        assertEquals("populationTotal",
                field(loaded, "population").fallbackMapping().propertyPid());
        assertNull(field(loaded, "area").fallbackMapping(),
                "a field with no fallback loads a null fallback");
    }

    private static GeneratedFieldModel field(GeneratedProjectModel model, String name) {
        return model.rootClass().fields().stream()
                .filter(f -> name.equals(f.name()))
                .findFirst().orElseThrow();
    }
}
