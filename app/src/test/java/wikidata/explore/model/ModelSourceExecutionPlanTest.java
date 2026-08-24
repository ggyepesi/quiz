package wikidata.explore.model;

import datasource.Datasources;
import datasource.api.BindingScope;
import datasource.api.SourceBindingSlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ModelSourceExecutionPlanTest {

    @Test void onePlanContainsPopulationIdentityNamesAndFieldValues() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel movie = new GeneratedClassModel("Movie");
        movie.instanceMapping().propertyPid("P31");
        movie.instanceMapping().sourceQid("Q11424");
        GeneratedFieldModel country = movie.addField(
                "country", FieldType.ENTITY, FieldCardinality.SINGLE);
        country.mapping().propertyPid("P495");
        model.rootClass(movie);

        var plan = ModelSourceExecutionPlan.compile(model, Datasources.standard());

        assertEquals(1, plan.steps(BindingScope.CLASS_POPULATION).size());
        assertEquals(2, plan.steps(BindingScope.CLASS_NAMES).size());
        assertEquals(1, plan.steps(BindingScope.CLASS_IDENTITY).size());
        assertEquals(1, plan.steps(BindingScope.FIELD_VALUE).size());
        assertNotNull(plan.step(datasource.api.SourceBindingTarget.fieldValue(
                "Movie", "country", SourceBindingSlot.PRIMARY_FIELD_VALUE)));
    }
}
