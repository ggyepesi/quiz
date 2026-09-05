package wikidata.explore.model;

import java.util.List;
import datasource.schema.FieldType;

import datasource.Datasources;
import datasource.api.BindingScope;
import datasource.api.SourceBindingSlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelSourceExecutionPlanTest {

    @Test void onePlanContainsPopulationIdentityNamesAndFieldValues() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel movie = new GeneratedClassModel("Movie");
        movie.membership(EntityBound.relation("P31", List.of("Q11424"), false));
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

    @Test void compilingStoredBindingsDoesNotProjectLegacyConfiguration() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel movie = new GeneratedClassModel("Movie");
        movie.membership(EntityBound.relation("P31", List.of("Q11424"), false));
        model.rootClass(movie);

        var plan = ModelSourceExecutionPlan.compileStored(model, Datasources.standard());

        assertTrue(plan.steps().isEmpty());
        assertTrue(movie.sourceBindings().isEmpty());
    }
}
