package wikidata.explore.model;

import datasource.schema.FieldType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OwnedClassPersistenceTest {

    @Test void explicitOwnedKindSurvivesSaveAndLoad(@TempDir Path directory)
            throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.ownedClass(true);
        project.addClass(name);
        var file = directory.resolve("owned.model.json").toFile();

        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        store.save(project, file);
        GeneratedProjectModel loaded = store.load(file);

        assertTrue(loaded.findClass("Name").ownedClass());
    }

    @Test void legacyOwnedFieldIsMigratedAtOneBoundary() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel owner = new GeneratedClassModel("Person");
        GeneratedFieldModel field = owner.addField(
                "structuredName", FieldType.ENTITY, FieldCardinality.SINGLE);
        field.entityClassName("Name");
        field.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel target = new GeneratedClassModel("Name");
        project.addClass(owner);
        project.addClass(target);

        OwnedClassSemantics.migrateLegacy(project);

        assertEquals(ClassKind.OWNED, target.classKind());
    }
}
