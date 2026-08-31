package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelUseTest {

    private static GeneratedClassModel imported(String name, String owner) {
        GeneratedClassModel clazz = new GeneratedClassModel(name);
        clazz.importedFrom(owner);
        return clazz;
    }

    @Test void aProjectThatImportsNothingUsesNothing() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addClass(new GeneratedClassModel("Prize"));

        assertEquals(List.of(), ModelUse.of(project));
        assertFalse(ModelUse.uses(project, "People"));
    }

    @Test void classesImportedFromOneModelAreReportedTogether() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addClass(new GeneratedClassModel("Prize"));
        project.addClass(imported("Person", "People"));
        project.addClass(imported("Name", "People"));
        project.addClass(imported("Place", "Geography"));

        List<ModelUse> uses = ModelUse.of(project);

        assertEquals(2, uses.size());
        assertEquals("People", uses.getFirst().modelName());
        assertEquals(List.of("Person", "Name"), uses.getFirst().classNames());
        assertEquals("Geography", uses.get(1).modelName());
        assertEquals(List.of("Place"), uses.get(1).classNames());
        assertTrue(ModelUse.uses(project, "People"));
        assertFalse(ModelUse.uses(project, "Astronomy"));
    }

    /**
     * The use is the adopted classes, so it cannot disagree with them. Removing the last
     * class adopted from a model ends the use with no second record to update — which is
     * the whole reason this is derived rather than declared.
     */
    @Test void aUseEndsWhenItsLastImportedClassGoes() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass(new GeneratedClassModel("Prize"));
        GeneratedClassModel name = imported("Name", "People");
        project.addClass(name);
        assertTrue(ModelUse.uses(project, "People"));

        project.removeClass(name);

        assertFalse(ModelUse.uses(project, "People"));
        assertEquals(List.of(), ModelUse.of(project));
    }

    @Test void aUseIsFoundWhateverTheCaseTheNameIsAskedIn() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addClass(imported("Name", "People"));

        assertTrue(ModelUse.uses(project, "people"));
        assertTrue(ModelUse.uses(project, "  People  "));
        assertFalse(ModelUse.uses(project, ""));
        assertFalse(ModelUse.uses(project, null));
    }

    @Test void anImportedClassReadsQualifiedAndALocalOneDoesNot() {
        assertEquals("People.Person", imported("Person", "People").qualifiedClassName());
        assertEquals("Person", new GeneratedClassModel("Person").qualifiedClassName());
    }
}
