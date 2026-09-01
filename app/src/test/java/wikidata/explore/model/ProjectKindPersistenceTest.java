package wikidata.explore.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectKindPersistenceTest {
    @TempDir Path temp;

    @Test void oldProjectsRemainDomains() {
        GeneratedProjectModel project = new GeneratedProjectModel();

        assertEquals(GeneratedProjectModel.ProjectKind.DOMAIN, project.projectKind());
        assertFalse(project.isModel());
        assertTrue(project.supportsExecution());
    }

    @Test void aModelKindRoundTrips() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("People");
        project.projectKind(GeneratedProjectModel.ProjectKind.MODEL);
        Path file = temp.resolve("People.model.json");

        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        store.save(project, file.toFile());
        GeneratedProjectModel loaded = store.load(file.toFile());

        assertEquals("People", loaded.name());
        assertTrue(loaded.isModel());
        assertFalse(loaded.supportsExecution());
    }

    @Test void copyingAProjectKeepsItsKind() {
        GeneratedProjectModel source = new GeneratedProjectModel();
        source.name("People");
        source.projectKind(GeneratedProjectModel.ProjectKind.MODEL);
        GeneratedProjectModel target = new GeneratedProjectModel();

        target.copyContentsFrom(source);

        assertTrue(target.isModel());
        assertEquals("People", target.name());
    }

    @Test void aChangedModelChangesItsImport() throws Exception {
        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        Path peopleFile = temp.resolve("people/people.model.json");
        GeneratedProjectModel people = new GeneratedProjectModel();
        people.name("People");
        people.projectKind(GeneratedProjectModel.ProjectKind.MODEL);
        people.rootClass(new GeneratedClassModel("Name"));
        people.rootClass().alias("Original name");
        store.save(people, peopleFile.toFile());

        GeneratedProjectModel nobel = new GeneratedProjectModel();
        nobel.name("Nobel");
        nobel.rootClass(new GeneratedClassModel("Prize"));
        ClassImportPlan.of(people, nobel, "Name").apply(
                java.util.Set.of("Name"), ClassImportPlan.Ownership.IMPORT);
        Path nobelFile = temp.resolve("nobel/nobel.model.json");
        store.save(nobel, nobelFile.toFile());
        String persisted = java.nio.file.Files.readString(nobelFile);
        assertTrue(persisted.contains("\"imports\""));
        assertFalse(persisted.contains("\"className\" : \"Name\""),
                "an import persists the reference, not a detached class copy");

        people.rootClass().alias("Changed in People");
        store.save(people, peopleFile.toFile());
        GeneratedProjectModel loaded = store.load(nobelFile.toFile());

        assertEquals("Changed in People", loaded.findClass("Name").alias());
        assertEquals("People", loaded.findClass("Name").importedFrom());
        assertEquals(1, loaded.imports().size());
    }

    @Test void nestedFieldsResolveToTheirDeclaringClass() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedFieldModel structured = person.addField("structuredName",
                datasource.schema.FieldType.ENTITY, FieldCardinality.SINGLE);
        GeneratedFieldModel given = new GeneratedFieldModel("givenName",
                datasource.schema.FieldType.STRING, FieldCardinality.SINGLE);
        structured.fields().add(given);
        project.rootClass(person);

        assertEquals(person, project.declaringClass(given));
    }
}
