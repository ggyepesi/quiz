package wikidata.explore.model;

import java.util.List;
import datasource.schema.FieldType;

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

    @Test void ownedSiteOverrideHasItsOwnResolvableAddressAndRoundTrips(
            @TempDir Path directory) throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.membership(EntityBound.relation("P31", List.of("Q5"), false));
        GeneratedFieldModel component = person.addField(
                "discovery", FieldType.ENTITY, FieldCardinality.SINGLE);
        component.entityClassName("Discovery");
        component.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel discovery = new GeneratedClassModel("Discovery");
        discovery.ownedClass(true);
        GeneratedFieldModel date = discovery.addField(
                "date", FieldType.DATE, FieldCardinality.SINGLE);
        date.mapping().propertyPid("P575");
        model.rootClass(person);
        model.addClass(discovery);

        OwnedFieldSource override = OwnedFieldSources.ensureOverride(component, "date");
        override.mapping().copyAcquisitionFrom(date.mapping());
        override.mapping().sourceType(FieldSourceType.WIKIPEDIA_INFOBOX);
        override.mapping().propertyPid("Infobox person.discovered");
        Path file = directory.resolve("model.json");

        new GeneratedProjectModelStore().save(model, file.toFile());
        GeneratedProjectModel loaded = new GeneratedProjectModelStore().load(file.toFile());
        GeneratedFieldModel restoredSite = loaded.findClass("Person").fields().getFirst();
        OwnedFieldSource restored = restoredSite.ownedFieldSources().getFirst();
        SourceBinding binding = restored.sourceBindings().getFirst();

        assertEquals("Infobox person.discovered", restored.mapping().propertyPid());
        assertEquals("Discovery", binding.target().className());
        assertEquals("date", binding.target().fieldPath());
        assertEquals("Person", binding.target().contextClassName());
        assertEquals("discovery", binding.target().contextFieldPath());
        assertNotNull(binding.resolve(Datasources.standard()));
    }

    private static GeneratedProjectModel model() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel movie = new GeneratedClassModel("Movie");
        movie.membership(EntityBound.relation("P31", List.of("Q11424"), false));
        movie.addField("locations", FieldType.ENTITY, FieldCardinality.COLLECTION);
        model.rootClass(movie);
        return model;
    }
}
