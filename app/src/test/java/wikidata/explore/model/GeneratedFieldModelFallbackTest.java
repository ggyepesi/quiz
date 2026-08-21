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
        withFallback.ensureWikipediaCategoryRule().pattern("Places with <value>");
        withFallback.wikipediaCategoryRule().policy(CategoryCandidatePolicy.EVIDENCE_ONLY);

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
        assertEquals("Places with <value>",
                field(loaded, "population").wikipediaCategoryRule().pattern());
        assertEquals(CategoryCandidatePolicy.EVIDENCE_ONLY,
                field(loaded, "population").wikipediaCategoryRule().policy());
        assertNull(field(loaded, "area").fallbackMapping(),
                "a field with no fallback loads a null fallback");
        assertNull(field(loaded, "area").wikipediaCategoryRule(),
                "a field with no category recipe preserves the old model shape");
    }

    private static GeneratedFieldModel field(GeneratedProjectModel model, String name) {
        return model.rootClass().fields().stream()
                .filter(f -> name.equals(f.name()))
                .findFirst().orElseThrow();
    }
}
