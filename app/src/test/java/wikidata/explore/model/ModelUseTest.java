package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelUseTest {

    private static GeneratedClassModel adopted(String name, String origin) {
        GeneratedClassModel clazz = new GeneratedClassModel(name);
        clazz.originModel(origin);
        return clazz;
    }

    @Test void aProjectThatAdoptedNothingUsesNothing() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addClass(new GeneratedClassModel("Prize"));

        assertEquals(List.of(), ModelUse.of(project));
        assertFalse(ModelUse.uses(project, "People"));
    }

    @Test void classesAdoptedFromOneModelAreReportedTogether() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addClass(new GeneratedClassModel("Prize"));
        project.addClass(adopted("Person", "People"));
        project.addClass(adopted("Name", "People"));
        project.addClass(adopted("Place", "Geography"));

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
    @Test void aUseEndsWhenItsLastAdoptedClassGoes() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass(new GeneratedClassModel("Prize"));
        GeneratedClassModel name = adopted("Name", "People");
        project.addClass(name);
        assertTrue(ModelUse.uses(project, "People"));

        project.removeClass(name);

        assertFalse(ModelUse.uses(project, "People"));
        assertEquals(List.of(), ModelUse.of(project));
    }

    @Test void aUseIsFoundWhateverTheCaseTheNameIsAskedIn() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addClass(adopted("Name", "People"));

        assertTrue(ModelUse.uses(project, "people"));
        assertTrue(ModelUse.uses(project, "  People  "));
        assertFalse(ModelUse.uses(project, ""));
        assertFalse(ModelUse.uses(project, null));
    }

    @Test void anAdoptedClassReadsQualifiedAndALocalOneDoesNot() {
        assertEquals("People.Person", adopted("Person", "People").qualifiedClassName());
        assertEquals("Person", new GeneratedClassModel("Person").qualifiedClassName());
    }
}
