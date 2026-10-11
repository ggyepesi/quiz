package wikidata.explore.extract;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.transform.OwnedComponents;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;

/** The derived owner is part of the normal snapshot graph, including its cycle. */
class OwnedInstanceOwnerPersistenceTest {

    @TempDir Path temporary;

    @Test void ownerReferenceSurvivesSaveAndLoadAsTheActualOwnerInstance()
            throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedFieldModel structuredName = person.addField(
                "structuredName", FieldType.ENTITY, FieldCardinality.SINGLE);
        structuredName.entityClassName("Name");
        structuredName.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.ownedClass(true);
        project.rootClass(person);
        project.addClass(name);

        WikidataDynamicObject owner = entity("Q42", "Person");
        OwnedComponents.apply(project, List.of(owner), null, null);
        WikidataDynamicObject component =
                (WikidataDynamicObject) owner.get("structuredName");
        Path snapshot = temporary.resolve("owned.json");
        new WikidataDynamicObjectJsonStore().saveWithFieldGraph(
                List.of(owner, component), snapshot.toFile(), project);

        List<WikidataDynamicObject> loaded =
                new WikidataDynamicObjectJsonStore().load(snapshot.toFile());
        WikidataDynamicObject loadedOwner = loaded.stream()
                .filter(value -> "Person".equals(value.typeKey())).findFirst().orElseThrow();
        WikidataDynamicObject loadedComponent = loaded.stream()
                .filter(value -> value.typeKey().startsWith("Name@"))
                .findFirst().orElseThrow();

        assertSame(loadedOwner, loadedComponent.get("owner"));
        assertSame(loadedComponent, loadedOwner.get("structuredName"));
    }

    private static WikidataDynamicObject entity(String id, String type) {
        WikidataDynamicObject value = new WikidataDynamicObject(id, id);
        value.type(type);
        value.typeKey(type);
        value.directClasses(List.of(type));
        return value;
    }
}
