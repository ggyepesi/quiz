package wikidata.explore.model;

import datasource.Datasources;
import datasource.api.SourceBindingSlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClassSourceBindingsTest {

    @Test void aSourceClassMakesItsPreviouslyImplicitSourcesExplicit() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel movie = new GeneratedClassModel("Movie");
        movie.instanceMapping().propertyPid("P31");
        movie.instanceMapping().sourceQid("Q11424");
        model.rootClass(movie);

        var plan = ModelSourceExecutionPlan.compile(model, Datasources.standard());

        assertEquals(4, plan.steps().size());
        assertTrue(movie.sourceBindings().stream().anyMatch(binding ->
                binding.target().slot() == SourceBindingSlot.CLASS_POPULATION));
        assertTrue(movie.sourceBindings().stream().anyMatch(binding ->
                binding.target().slot() == SourceBindingSlot.CLASS_IDENTITY));
        assertTrue(movie.sourceBindings().stream().anyMatch(binding ->
                binding.target().slot() == SourceBindingSlot.CLASS_LABEL));
        assertTrue(movie.sourceBindings().stream().anyMatch(binding ->
                binding.target().slot() == SourceBindingSlot.CLASS_ALIASES));
    }

    @Test void ownedAndStatementClassesDoNotPretendToHaveEntityIdentitySources() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel owned = new GeneratedClassModel("Name");
        owned.classKind(ClassKind.OWNED);
        model.rootClass(owned);

        ClassSourceBindings.synchronize(model);

        assertTrue(owned.sourceBindings().isEmpty());
    }

    @Test void explicitlyRemovedAliasesStayRemovedWhenThePlanIsRecompiled() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.instanceMapping().propertyPid("P31");
        person.instanceMapping().sourceQid("Q5");
        model.rootClass(person);

        ClassSourceBindings.synchronize(model);
        ClassSourceBindings.aliases(person, false);
        ClassSourceBindings.synchronize(model);

        assertNull(ClassSourceBindings.binding(
                person, SourceBindingSlot.CLASS_ALIASES));
        assertNotNull(ClassSourceBindings.binding(
                person, SourceBindingSlot.CLASS_IDENTITY));
        assertNotNull(ClassSourceBindings.binding(
                person, SourceBindingSlot.CLASS_LABEL));
    }

    /**
     * The project-less form exists for an editor that has no project: it needs the
     * identity/label/alias defaults and never reads the population slot. Deriving the
     * population there would read the class's OWN mapping and find nothing, clearing an
     * inherited one — which describing a subclass in the workbench used to do, on a
     * method whose whole job was building a label string.
     */
    @Test void describingOneClassDoesNotClearAPopulationItCannotSee() {
        GeneratedProjectModel model = inheritedPopulation();
        GeneratedClassModel person = model.findClass("Person");
        ClassSourceBindings.synchronize(model);
        assertNotNull(ClassSourceBindings.binding(
                person, SourceBindingSlot.CLASS_POPULATION));

        ClassSourceBindings.synchronize(person);   // the class alone, as an editor has it

        assertNotNull(ClassSourceBindings.binding(
                        person, SourceBindingSlot.CLASS_POPULATION),
                "an inherited population survives being described");
    }

    @Test void aSubclassGetsAnExplicitBindingForItsInheritedPopulation() {
        GeneratedProjectModel model = inheritedPopulation();

        var plan = ModelSourceExecutionPlan.compile(model, Datasources.standard());
        var inherited = plan.step(
                datasource.api.SourceBindingTarget.classPopulation("Person"));

        assertNotNull(inherited);
        assertEquals("P1411", inherited.recipe().parameter("property"));
        assertEquals("Q19020", inherited.recipe().parameter("values"));
    }

    /** Person takes its membership from Nominee and declares only a discriminator. */
    private static GeneratedProjectModel inheritedPopulation() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nominee = new GeneratedClassModel("Nominee");
        nominee.instanceMapping().propertyPid("P1411");
        nominee.instanceMapping().sourceQid("Q19020");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.baseClassName("Nominee");
        person.discriminatorPid("P31");
        person.discriminatorQid("Q5");
        model.rootClass(nominee);
        model.addClass(person);
        return model;
    }
}
