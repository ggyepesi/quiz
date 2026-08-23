package wikidata.explore.model;

import datasource.Datasources;
import datasource.api.SourceBinding;
import datasource.api.SourceBindingSlot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FieldSourceBindingsTest {

    @Test void legacyFieldSourcesBecomeResolvableTypedBindings() {
        GeneratedProjectModel model = model();
        GeneratedFieldModel field = model.rootClass().fields().getFirst();
        field.mapping().sourceType(FieldSourceType.SPARQL);
        field.mapping().propertyPid("P840");
        FieldSourceMapping fallback = field.ensureFallbackMapping();
        fallback.sourceType(FieldSourceType.WIKIPEDIA_INFOBOX);
        fallback.propertyPid("Infobox film.country");
        field.ensureWikipediaCategoryRule().pattern("Films set in <value>");

        FieldSourceBindings.synchronizeForSave(model);

        assertEquals(3, field.sourceBindings().size());
        for (SourceBinding binding : field.sourceBindings()) {
            assertEquals(datasource.api.BindingScope.FIELD_VALUE,
                    binding.resolve(Datasources.standard()).scope());
        }
    }

    @Test void aTypedFallbackProjectsToTheExistingExecutionModel() {
        GeneratedProjectModel model = model();
        GeneratedFieldModel field = model.rootClass().fields().getFirst();
        SourceBinding replacement = new SourceBinding(
                datasource.api.SourceBindingTarget.fieldValue(
                        "Movie", "locations", SourceBindingSlot.FALLBACK_FIELD_VALUE),
                new datasource.api.SourceRecipe("dbpedia", "property",
                        java.util.Map.of("property", "country", "label", "Country")));

        FieldSourceBindings.put(field, replacement);

        assertEquals(FieldSourceType.DBPEDIA, field.fallbackMapping().sourceType());
        assertEquals("country", field.fallbackMapping().propertyPid());
        assertSame(replacement,
                FieldSourceBindings.binding(field, SourceBindingSlot.FALLBACK_FIELD_VALUE));
    }

    @Test void anExternalPrimaryResolvesToItsActualProvider() {
        GeneratedProjectModel model = model();
        GeneratedFieldModel field = model.rootClass().fields().getFirst();
        field.mapping().sourceType(FieldSourceType.DBPEDIA);
        field.mapping().propertyPid("country");

        FieldSourceBindings.synchronizeForSave(model);
        SourceBinding primary = FieldSourceBindings.binding(
                field, SourceBindingSlot.PRIMARY_FIELD_VALUE);

        assertEquals("dbpedia", primary.recipe().providerId());
        assertEquals("property", primary.recipe().operationId());
    }

    @Test void bindingsSurviveTheModelFileAndRestoreLegacyProjection(
            @TempDir Path directory) throws Exception {
        GeneratedProjectModel model = model();
        GeneratedFieldModel field = model.rootClass().fields().getFirst();
        field.ensureFallbackMapping().sourceType(FieldSourceType.DBPEDIA);
        field.fallbackMapping().propertyPid("country");
        Path file = directory.resolve("model.json");

        new GeneratedProjectModelStore().save(model, file.toFile());
        GeneratedProjectModel loaded = new GeneratedProjectModelStore().load(file.toFile());
        GeneratedFieldModel restored = loaded.rootClass().fields().getFirst();

        assertNotNull(FieldSourceBindings.binding(
                restored, SourceBindingSlot.FALLBACK_FIELD_VALUE));
        assertEquals(FieldSourceType.DBPEDIA, restored.fallbackMapping().sourceType());
        assertEquals("country", restored.fallbackMapping().propertyPid());
    }

    private static GeneratedProjectModel model() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel movie = new GeneratedClassModel("Movie");
        movie.instanceMapping().sourceQid("Q11424");
        movie.instanceMapping().propertyPid("P31");
        movie.addField("locations", FieldType.ENTITY, FieldCardinality.COLLECTION);
        model.rootClass(movie);
        return model;
    }
}
