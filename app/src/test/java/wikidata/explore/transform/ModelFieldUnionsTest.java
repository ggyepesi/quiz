package wikidata.explore.transform;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A field union is the one offline operation that presents several already-produced
 * routes as one collection. The Solar System shape is deliberate: the direct P397
 * inverse and the MoonKind route are both retained, and the union runs after those
 * inverses in the shared replayable sequence.
 */
class ModelFieldUnionsTest {

    @Test void aUnionRunsAfterInvertsAndKeepsTheFirstOccurrenceOfEachEntity() {
        GeneratedProjectModel project = project();
        WikidataDynamicObject jupiter = object("Q319", "Jupiter", "Planet");
        WikidataDynamicObject io = object("Q3123", "Io", "Moon");
        WikidataDynamicObject newMoon = object("Q999", "New moon", "Moon");
        WikidataDynamicObject jovianKind =
                object("Q61702557", "moon of Jupiter", "MoonKind");

        io.put("parentBody", jupiter);
        jovianKind.put("parentBody", jupiter);
        jovianKind.put("moons", new ArrayList<>(List.of(io, newMoon)));
        List<WikidataDynamicObject> pool =
                new ArrayList<>(List.of(jupiter, io, newMoon, jovianKind));

        int changed = StatementTransforms.applyIdempotent(
                ProjectModelCompiler.compile(project), pool, null);

        assertEquals(1, changed, "only Planet.allMoons is newly filled");
        List<?> allMoons = (List<?>) jupiter.get("allMoons");
        assertEquals(List.of(io, newMoon), allMoons,
                "the direct moon stays first and its MoonKind occurrence is deduplicated");
        assertSame(io, allMoons.getFirst(),
                "the union retains the existing object, not a copied reference");
        assertEquals(0, StatementTransforms.applyIdempotent(
                        ProjectModelCompiler.compile(project), pool, null),
                "replaying a loaded snapshot is idempotent");
    }

    @Test void editableAndCompiledModelsDeriveTheSameUnion() {
        GeneratedProjectModel project = project();

        assertEquals(ModelFieldUnions.derive(project),
                ModelFieldUnions.derive(ProjectModelCompiler.compile(project)));
    }

    private static GeneratedProjectModel project() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel planet = new GeneratedClassModel("Planet");
        GeneratedClassModel moon = new GeneratedClassModel("Moon");
        GeneratedClassModel moonKind = new GeneratedClassModel("MoonKind");
        project.rootClass(planet);
        project.addClass(moon);
        project.addClass(moonKind);

        GeneratedFieldModel moonParent = moon.addField(
                "parentBody", FieldType.ENTITY, FieldCardinality.SINGLE);
        moonParent.entityClassName("Planet");
        GeneratedFieldModel direct = planet.addField(
                "moons", FieldType.ENTITY, FieldCardinality.COLLECTION);
        direct.entityClassName("Moon");
        direct.mapping().productionKind(FieldProductionKind.INVERT);
        direct.mapping().inverseField("parentBody");

        GeneratedFieldModel kindParent = moonKind.addField(
                "parentBody", FieldType.ENTITY, FieldCardinality.SINGLE);
        kindParent.entityClassName("Planet");
        GeneratedFieldModel typed = moonKind.addField(
                "moons", FieldType.ENTITY, FieldCardinality.COLLECTION);
        typed.entityClassName("Moon");
        GeneratedFieldModel kinds = planet.addField(
                "moonKinds", FieldType.ENTITY, FieldCardinality.COLLECTION);
        kinds.entityClassName("MoonKind");
        kinds.mapping().productionKind(FieldProductionKind.INVERT);
        kinds.mapping().inverseField("parentBody");

        GeneratedFieldModel all = planet.addField(
                "allMoons", FieldType.ENTITY, FieldCardinality.COLLECTION);
        all.entityClassName("Moon");
        all.mapping().productionKind(FieldProductionKind.UNION);
        all.mapping().unionSourcePaths().addAll(
                List.of("moons", "moonKinds.moons"));
        return project;
    }

    private static WikidataDynamicObject object(
            String qid, String label, String type) {
        WikidataDynamicObject object = new WikidataDynamicObject(qid, label);
        object.type(type);
        return object;
    }
}
