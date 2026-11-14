package wikidata.explore.model;

import java.util.List;
import datasource.schema.FieldType;

import datasource.Datasources;
import datasource.api.BindingScope;
import datasource.api.SourceBindingSlot;
import datasource.api.SourceBinding;
import datasource.api.SourceBindingTarget;
import datasource.api.SourceRecipe;
import datasource.pageviews.PageviewsDatasourceProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test void aConcreteDatasourceOutputMustMatchTheFieldTypeAndCardinality() {
        GeneratedProjectModel model = modelWithViews(FieldType.STRING,
                FieldCardinality.SINGLE, true);

        IllegalArgumentException wrongType = assertThrows(IllegalArgumentException.class,
                () -> ModelSourceExecutionPlan.compile(model, Datasources.standard()));
        assertTrue(wrongType.getMessage().contains("produces Number, but the field is String"),
                wrongType.getMessage());

        GeneratedProjectModel list = modelWithViews(FieldType.NUMBER,
                FieldCardinality.COLLECTION, true);
        IllegalArgumentException wrongCardinality = assertThrows(
                IllegalArgumentException.class,
                () -> ModelSourceExecutionPlan.compile(list, Datasources.standard()));
        assertTrue(wrongCardinality.getMessage().contains(
                "produces one value, but the field is a list"),
                wrongCardinality.getMessage());
    }

    /** The mismatch Generate refuses is named in the problem list first, as a warning so
     *  the model can still be saved while it is being corrected. */
    @Test void aDatasourceOutputTheFieldCannotHoldIsAValidationWarning() {
        GeneratedProjectModel model = modelWithViews(FieldType.STRING,
                FieldCardinality.SINGLE, true);

        GeneratedProjectModelValidator.ValidationResult validation =
                GeneratedProjectModelValidator.validate(model);

        assertTrue(validation.valid(), validation.format());
        assertTrue(validation.format().contains("produces Number, but the field is String"),
                validation.format());
        String matching = GeneratedProjectModelValidator.validate(modelWithViews(
                FieldType.NUMBER, FieldCardinality.SINGLE, true)).format();
        assertTrue(!matching.contains("cannot fill"), matching);
    }

    @Test void datasourceLoadMethodNeedsExactlyOneChosenOperation() {
        GeneratedProjectModel model = modelWithViews(
                FieldType.NUMBER, FieldCardinality.SINGLE, false);

        GeneratedProjectModelValidator.ValidationResult validation =
                GeneratedProjectModelValidator.validate(model);
        assertTrue(validation.format().contains("no operation is chosen"),
                validation.format());
        assertThrows(IllegalArgumentException.class,
                () -> ModelSourceExecutionPlan.compile(model, Datasources.standard()));
    }

    private static GeneratedProjectModel modelWithViews(FieldType type,
            FieldCardinality cardinality, boolean bind) {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel article = new GeneratedClassModel("Article");
        GeneratedFieldModel views = article.addField("views", type, cardinality);
        views.mapping().sourceType(FieldSourceType.DATASOURCE);
        model.rootClass(article);
        if (bind) {
            FieldSourceBindings.put(views, new SourceBinding(
                    SourceBindingTarget.fieldValue("Article", "views",
                            SourceBindingSlot.PRIMARY_FIELD_VALUE),
                    new SourceRecipe(PageviewsDatasourceProvider.ID,
                            PageviewsDatasourceProvider.ARTICLE_VIEWS,
                            java.util.Map.of(PageviewsDatasourceProvider.MONTHS, "12"))));
        }
        return model;
    }
}
