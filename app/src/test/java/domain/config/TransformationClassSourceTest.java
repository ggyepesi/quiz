package domain.config;

import domain.DomainField;
import domain.transform.FilterCondition;
import domain.transform.FilterOperator;
import objectview.field.FieldPath;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Promoting a filter chain into a transformed class is one declaration edit shared by
 * TransformApp's Save and the unified workbench's Create transformed class. A refused
 * promotion must leave the open configuration exactly as it was: no half-declared class,
 * no orphan population publication.
 */
class TransformationClassSourceTest {

    @Test void aRefusedPromotionChangesNothing() {
        var model = new GeneratedProjectModel();
        var input = new GeneratedClassModel("Position");
        model.rootClass(input);
        var frozen = new PopulationSelection("HeldPositionPopulation");
        frozen.className("Position");
        model.replaceSelection(frozen);
        var output = new GeneratedClassModel("HeldPosition");
        var mode = output.populationMode();

        assertThrows(IllegalArgumentException.class, () -> TransformationClassSource.applyFilters(
                model, output, "Position", List.of(condition())));

        assertSame(frozen, model.findSelection("HeldPositionPopulation"),
                "the existing population of another class is not retargeted");
        assertEquals("Position", frozen.className());
        assertNull(output.transformationSource());
        assertEquals(mode, output.populationMode());
    }

    @Test void aMissingInputClassIsRefusedBeforeAnyEdit() {
        var model = new GeneratedProjectModel();
        model.rootClass(new GeneratedClassModel("Position"));
        var output = new GeneratedClassModel("HeldPosition");

        assertThrows(IllegalArgumentException.class, () -> TransformationClassSource.applyFilters(
                model, output, "Office", List.of(condition())));

        assertNull(model.findSelection("HeldPositionPopulation"), "no orphan publication");
        assertNull(output.transformationSource());
    }

    @Test void anAcceptedPromotionDeclaresTheRecipeAndItsPublication() {
        var model = new GeneratedProjectModel();
        model.rootClass(new GeneratedClassModel("Position"));
        var output = new GeneratedClassModel("HeldPosition");

        var publication = TransformationClassSource.applyFilters(
                model, output, "Position", List.of(condition()));

        assertSame(publication, model.findSelection("HeldPositionPopulation"));
        assertEquals("HeldPosition", publication.className());
        assertEquals(ClassPopulationMode.MATERIALIZED_TRANSFORM, output.populationMode());
        assertEquals("holderCount", output.transformationSource().steps().getFirst().fieldPath());
    }

    private static FilterCondition condition() {
        return new FilterCondition(new DomainField("Position", FieldPath.of("holderCount"),
                false, false), FilterOperator.GREATER_THAN, 0, null);
    }
}
