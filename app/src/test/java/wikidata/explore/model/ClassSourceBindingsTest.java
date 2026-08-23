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

        var resolved = ClassSourceBindings.synchronizeAndResolve(model, Datasources.standard());

        assertEquals(4, resolved.size());
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
}
