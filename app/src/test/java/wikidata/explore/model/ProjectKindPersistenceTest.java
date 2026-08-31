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

    @Test void aModelKindAndItsStaticStateRoundTrip() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("People");
        project.projectKind(GeneratedProjectModel.ProjectKind.MODEL);
        project.staticModel(true);
        Path file = temp.resolve("People.model.json");

        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        store.save(project, file.toFile());
        GeneratedProjectModel loaded = store.load(file.toFile());

        assertEquals("People", loaded.name());
        assertTrue(loaded.isModel());
        assertFalse(loaded.supportsExecution());
        assertTrue(loaded.staticModel());
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

    /**
     * Origin that does not survive a save is no origin at all: the adopting project
     * would reopen with no record of where its classes came from.
     */
    @Test void anAdoptedClassOriginSurvivesASave() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("Nobel");
        GeneratedClassModel adopted = new GeneratedClassModel("Name");
        adopted.originModel("People");
        project.addClass(adopted);
        project.addClass(new GeneratedClassModel("Prize"));
        Path file = temp.resolve("Nobel.model.json");

        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        store.save(project, file.toFile());
        GeneratedProjectModel loaded = store.load(file.toFile());

        assertEquals("People", loaded.findClass("Name").originModel());
        assertEquals("", loaded.findClass("Prize").originModel(),
                "a class authored here still reports no origin after a reload");
    }
}
