package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldRecipesTest {

    @Test void constellationRecipesCannotRewriteAPersonField() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        var spouse = person.addField(
                "spouse", FieldType.ENTITY, FieldCardinality.COLLECTION);
        spouse.entityClassName("Person");
        spouse.mapping().propertyPid("P26");
        model.addClass(person);

        assertTrue(FieldRecipes.applicableTo(model, spouse).isEmpty());
        assertEquals("Person", spouse.entityClassName());
        assertEquals("P26", spouse.mapping().propertyPid());
    }

    @Test void theWorkedRecipesRemainAvailableOnConstellationFields() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel constellation = new GeneratedClassModel("Constellation");
        constellation.instanceMapping().sourceQid("Q8928");
        var field = constellation.addField(
                "stars", FieldType.AUTO, FieldCardinality.AUTO);
        model.addClass(constellation);

        assertFalse(FieldRecipes.applicableTo(model, field).isEmpty());
    }

    @Test void applicabilityFollowsTheConfiguredConceptRatherThanTheClassName() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel renamed = new GeneratedClassModel("SkyFigure");
        renamed.instanceMapping().sourceQid("Q8928");
        var field = renamed.addField("neighbours", FieldType.AUTO, FieldCardinality.AUTO);
        model.addClass(renamed);

        assertFalse(FieldRecipes.applicableTo(model, field).isEmpty());
        FieldRecipe bordering = FieldRecipes.applicableTo(model, field).stream()
                .filter(recipe -> recipe.goal().startsWith("Bordering"))
                .findFirst().orElseThrow();
        bordering.applyTo(field, model);
        assertEquals("SkyFigure", field.entityClassName());
    }

    @Test void aForeignFieldHasNoInventedDeclaringClass() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedFieldModel foreign = new GeneratedFieldModel();

        assertEquals(null, model.declaringClass(foreign));
        assertEquals(null, ModelSourceWorkbenchPanel.fieldSampleContext(model, foreign));
    }
}
