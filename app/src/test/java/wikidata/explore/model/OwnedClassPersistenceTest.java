package wikidata.explore.model;

import datasource.schema.FieldType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OwnedClassPersistenceTest {

    @Test void explicitOwnedKindSurvivesSaveAndLoad(@TempDir Path directory)
            throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.ownedClass(true);
        project.addClass(name);
        project.addClass(new GeneratedClassModel("Owner"));
        var file = directory.resolve("owned.model.json").toFile();

        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        store.save(project, file);
        GeneratedProjectModel loaded = store.load(file);

        GeneratedClassModel loadedName = loaded.findClass("Name");
        assertTrue(loadedName.ownedClass());
        GeneratedFieldModel owner = loadedName.fields().stream()
                .filter(field -> field.name().equals("owner"))
                .findFirst().orElseThrow();
        assertEquals(1, loadedName.fields().stream()
                .filter(field -> field.name().equals("owner")).count());
        assertTrue(OwnedClassSemantics.isOwnerReferenceField(loadedName, owner),
                "the owner reference is persisted as part of the class schema");
        assertEquals("Owner owner [reference]", owner.toString(),
                "Viewable is a Java interface, never the owner field's model class");
        assertTrue(loaded.findClass("Owner") != null,
                "the explicitly authored fallback role survives persistence");
    }

    @Test void oneKnownProductionClassIsTheOwnerFieldsConcreteType() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedFieldModel nameField = person.addField(
                "structuredName", FieldType.ENTITY, FieldCardinality.SINGLE);
        nameField.entityClassName("Name");
        nameField.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.ownedClass(true);
        project.rootClass(person);
        project.addClass(name);

        OwnedClassSemantics.synchronizeOwnerReferences(project);

        GeneratedFieldModel owner = name.fields().stream()
                .filter(field -> field.name().equals("owner"))
                .findFirst().orElseThrow();
        assertEquals("Person", owner.entityClassName());
        assertFalse(owner.unclassedEntity());
        assertEquals("Person owner [reference]", owner.toString());
    }

    @Test void severalProductionClassesUseOneNamedOwnerRole() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel constellation = project.rootClass();
        constellation.className("Constellation");
        GeneratedClassModel celestialBody = new GeneratedClassModel("CelestialBody");
        GeneratedClassModel discovery = new GeneratedClassModel("Discovery");
        discovery.ownedClass(true);
        for (GeneratedClassModel producer : java.util.List.of(
                constellation, celestialBody)) {
            GeneratedFieldModel site = producer.addField(
                    "discovery", FieldType.ENTITY, FieldCardinality.SINGLE);
            site.entityClassName("Discovery");
            site.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        }
        project.addClass(celestialBody);
        project.addClass(discovery);
        project.addClass(new GeneratedClassModel("Owner"));

        OwnedClassSemantics.synchronizeOwnerReferences(project);
        GeneratedClassModel role = project.findClass("Owner");
        GeneratedFieldModel owner = discovery.fields().stream()
                .filter(field -> field.name().equals("owner"))
                .findFirst().orElseThrow();
        project.representationClasses(role,
                java.util.List.of("Constellation", "CelestialBody"));

        assertEquals("Owner", owner.entityClassName());
        assertFalse(owner.unclassedEntity());
        assertTrue(GeneratedProjectModelValidator.validate(project).valid(),
                "the explicit production sites admit their already-known owner types");
    }

    @Test void aRequiredOwnerRoleIsReportedButNeverCreated(@TempDir Path directory) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel discovery = new GeneratedClassModel("Discovery");
        discovery.ownedClass(true);
        for (String className : java.util.List.of("Constellation", "CelestialBody")) {
            GeneratedClassModel producer = new GeneratedClassModel(className);
            GeneratedFieldModel site = producer.addField(
                    "discovery", FieldType.ENTITY, FieldCardinality.SINGLE);
            site.entityClassName("Discovery");
            site.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
            project.addClass(producer);
        }
        project.addClass(discovery);

        OwnedClassSemantics.synchronizeOwnerReferences(project);
        GeneratedProjectModelValidator.ValidationResult result =
                GeneratedProjectModelValidator.validate(project);

        assertTrue(project.findClass("Owner") == null,
                "normalization must not author vocabulary on the user's behalf");
        assertEquals("Owner", discovery.fields().stream()
                .filter(field -> field.name().equals("owner"))
                .findFirst().orElseThrow().entityClassName(),
                "the derived target still states which authored role is required");
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(problem ->
                problem.message().contains("Add the 'Owner' class explicitly")),
                result.format());
        assertThrows(java.io.IOException.class, () ->
                new GeneratedProjectModelStore().save(
                        project, directory.resolve("missing-owner.model.json").toFile()));
        assertTrue(project.findClass("Owner") == null,
                "a refused save must not leave an invented Owner behind");
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
        assertTrue(target.fields().stream().anyMatch(candidate ->
                OwnedClassSemantics.isOwnerReferenceField(target, candidate)));
    }

    @Test void changingAwayFromOwnedRemovesOnlyTheDerivedOwnerField() {
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.ownedClass(true);
        name.addField("givenName", FieldType.STRING, FieldCardinality.SINGLE);

        name.classKind(ClassKind.SOURCE);

        assertFalse(name.fields().stream().anyMatch(field ->
                field.name().equals(OwnedClassSemantics.OWNER_FIELD)));
        assertTrue(name.fields().stream().anyMatch(field ->
                field.name().equals("givenName")));
    }

    @Test void producerDiscoveryFollowsTheStableClassReferenceNotItsNameHint() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedFieldModel component = person.addField(
                "structuredName", FieldType.ENTITY, FieldCardinality.SINGLE);
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.ownedClass(true);
        project.rootClass(person);
        project.addClass(name);
        project.ensureDeclarationIdentities();
        component.entityReference(name.declarationId(), "NameBeforeRename");
        component.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);

        OwnedClassSemantics.synchronizeOwnerReferences(project);

        assertEquals("Person", name.fields().stream()
                .filter(field -> OwnedClassSemantics.isOwnerReferenceField(name, field))
                .findFirst().orElseThrow().entityClassName());
    }
}
