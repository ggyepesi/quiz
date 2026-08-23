package datasource.api;

import datasource.Datasources;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SourceBindingTest {

    @Test void fieldBindingsRequireAFieldButPopulationBindingsRejectOne() {
        assertThrows(IllegalArgumentException.class, () -> new SourceBindingTarget(
                BindingScope.FIELD_VALUE, "Movie", "", SourceBindingSlot.FALLBACK_FIELD_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new SourceBindingTarget(
                BindingScope.CLASS_POPULATION, "Movie", "locations",
                SourceBindingSlot.CLASS_POPULATION));
    }

    @Test void replacementIdentityBelongsToTheTargetRatherThanTheProvider() {
        SourceBindingTarget target = SourceBindingTarget.fieldValue(
                "Movie", "locations", SourceBindingSlot.FALLBACK_FIELD_VALUE);
        SourceBinding dbpedia = new SourceBinding(target,
                new SourceRecipe("dbpedia", "property", Map.of("property", "country")));
        SourceBinding wikipedia = new SourceBinding(target,
                new SourceRecipe("wikipedia", "infobox-parameter",
                        Map.of("property", "Infobox film.country")));

        assertTrue(dbpedia.sameTarget(wikipedia));
        assertNotEquals(dbpedia.recipe(), wikipedia.recipe());
    }

    @Test void resolutionChecksTheScopeOfTheAttachment() {
        SourceBinding category = new SourceBinding(
                SourceBindingTarget.fieldValue("Movie", "locations",
                        SourceBindingSlot.CATEGORY_EVIDENCE),
                new SourceRecipe("wikipedia", "category", Map.of()));

        assertEquals(BindingScope.FIELD_VALUE,
                category.resolve(Datasources.standard()).scope());
        SourceBinding wrong = new SourceBinding(
                SourceBindingTarget.classPopulation("Movie"), category.recipe());
        assertThrows(IllegalArgumentException.class,
                () -> wrong.resolve(Datasources.standard()));
    }

    @Test void slotIdsAreValidatedAtTheLegacyBoundary() {
        assertEquals(SourceBindingSlot.FALLBACK_FIELD_VALUE,
                SourceBindingSlot.require("additional-source"));
        assertThrows(IllegalArgumentException.class,
                () -> SourceBindingSlot.require("additional-soruce"));
        assertThrows(IllegalArgumentException.class, () -> new SourceBindingTarget(
                BindingScope.FIELD_VALUE, "Movie", "locations",
                SourceBindingSlot.CLASS_POPULATION));
    }
}
