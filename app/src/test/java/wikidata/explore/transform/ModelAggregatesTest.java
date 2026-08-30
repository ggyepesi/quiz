package wikidata.explore.transform;

import datasource.schema.FieldType;

import org.junit.jupiter.api.Test;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelAggregatesTest {
    @Test void groupsSourceRecordsAndIsReplayableWithoutDuplicates() {
        GeneratedProjectModel model = model();
        WikidataDynamicObject physics = entity("Q38104", "Physics", "Category");
        WikidataDynamicObject a = group("S1", physics, "1921", "A");
        WikidataDynamicObject b = group("S2", physics, "1921", "B");
        WikidataDynamicObject c = group("S3", physics, "1922", "C");
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(physics, a, b, c));

        assertEquals(2, ModelAggregates.apply(ProjectModelCompiler.compile(model), pool, null));
        WikidataDynamicObject prize1921 = pool.stream()
                .filter(o -> o.directClassNames().contains("NobelPrize"))
                .filter(o -> "1921".equals(o.get("year"))).findFirst().orElseThrow();
        assertEquals(List.of(a, b), prize1921.get("laureateGroups"));
        assertSame(physics, prize1921.get("category"));

        assertEquals(2, ModelAggregates.apply(ProjectModelCompiler.compile(model), pool, null));
        assertEquals(2, pool.stream()
                .filter(o -> o.directClassNames().contains("NobelPrize")).count());
    }

    @Test void malformedAggregateFailsBeforeExecution() {
        GeneratedProjectModel model = model();
        model.rootClass().aggregateSource().membersField("missing");
        var result = GeneratedProjectModelValidator.validate(model);
        assertFalse(result.valid());
        assertTrue(result.toString().contains("members field"), result.toString());
    }

    @Test void missingKeyIsExcludedByDefaultRatherThanCreatingAPhantomGroup() {
        GeneratedProjectModel model = model();
        WikidataDynamicObject physics = entity("Q38104", "Physics", "Category");
        WikidataDynamicObject missing = group("S1", physics, null, "unknown year");
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(physics, missing));

        assertEquals(0, ModelAggregates.apply(ProjectModelCompiler.compile(model), pool, null));
        assertTrue(pool.stream().noneMatch(o -> o.directClassNames().contains("NobelPrize")));
    }

    @Test void framedAggregateIdentityCannotCollideOnDelimiterCharacters() {
        assertNotEquals(
                AggregateIdentity.identifier("Prize", List.of("a|b", "c")),
                AggregateIdentity.identifier("Prize", List.of("a", "b|c")));
    }

    @Test void canonicalSpecCannotBecomeASecondAggregateIdentity() {
        GeneratedProjectModel model = model();
        model.rootClass().canonical().keyFields().add("year");
        assertTrue(ProjectModelCompiler.compile(model).rootClass()
                .canonical().keyFields().isEmpty());
    }

    @Test void aggregateRecipeSurvivesModelSaveAndLoad() throws Exception {
        GeneratedProjectModel model = model();
        java.io.File file = java.io.File.createTempFile("aggregate-model", ".json");
        file.deleteOnExit();
        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        store.save(model, file);

        GeneratedClassModel loaded = store.load(file).rootClass();
        assertEquals(ClassKind.AGGREGATE, loaded.classKind());
        assertEquals("LaureateGroup", loaded.aggregateSource().sourceClassName());
        assertEquals(List.of("category", "year"), loaded.aggregateSource().keys().stream()
                .map(AggregateClassSource.Key::targetField).toList());
    }

    private static GeneratedProjectModel model() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel prize = new GeneratedClassModel("NobelPrize");
        prize.classKind(ClassKind.AGGREGATE);
        prize.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Category");
        prize.addField("year", FieldType.STRING, FieldCardinality.SINGLE);
        prize.addField("laureateGroups", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .entityClassName("LaureateGroup");
        AggregateClassSource aggregate = new AggregateClassSource(
                "LaureateGroup", "laureateGroups");
        aggregate.keys().add(new AggregateClassSource.Key("category", "category"));
        aggregate.keys().add(new AggregateClassSource.Key("year", "year"));
        prize.aggregateSource(aggregate);
        model.rootClass(prize);

        GeneratedClassModel group = new GeneratedClassModel("LaureateGroup");
        group.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Category");
        group.addField("year", FieldType.STRING, FieldCardinality.SINGLE);
        model.addClass(group);
        model.addClass(new GeneratedClassModel("Category"));
        return model;
    }

    private static WikidataDynamicObject group(String id, Object category,
            String year, String name) {
        WikidataDynamicObject value = entity(id, name, "LaureateGroup");
        value.put("category", category);
        value.put("year", year);
        return value;
    }

    private static WikidataDynamicObject entity(String id, String name, String type) {
        WikidataDynamicObject value = new WikidataDynamicObject(id, name);
        value.type(type);
        return value;
    }
}
