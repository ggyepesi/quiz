package wikidata.explore.advisor;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldSourceType;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.RuleDirection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelExplanationFactoryTest {

    @Test void explainsPopulationUsingEffectiveGeneratedDirection() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("Cities");
        GeneratedClassModel city = new GeneratedClassModel("City");
        GeneratedFieldModel population = city.addField(
                "population", FieldType.NUMBER, FieldCardinality.SINGLE);
        population.mapping().propertyPid("P1082");
        population.mapping().propertyLabel("population");
        // This is the model default, but query generation normalizes literals.
        population.mapping().direction(RuleDirection.ITEM_TO_ROOT);
        project.rootClass(city);

        ModelElementExplanation explanation = ModelExplanationFactory.explain(
                new DecisionContext(project, city, population, null));

        assertEquals(ModelElementExplanation.Scope.FIELD, explanation.scope());
        assertEquals("Cities › City › population", explanation.breadcrumb());
        assertEquals(RuleDirection.ROOT_TO_ITEM,
                explanation.sourceRoutes().getFirst().direction());
        assertEquals("?city wdt:P1082 ?population .", explanation.example());
        assertTrue(explanation.advice().stream()
                .anyMatch(a -> a.contains("configured item → root direction")));
    }

    @Test void explainsDbpediaAsPostExtractionSource() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel city = new GeneratedClassModel("City");
        GeneratedFieldModel population = city.addField(
                "population", FieldType.NUMBER, FieldCardinality.SINGLE);
        population.mapping().sourceType(FieldSourceType.DBPEDIA);
        population.mapping().propertyPid("populationTotal");
        project.rootClass(city);

        ModelElementExplanation explanation =
                ModelExplanationFactory.explainField(project, city, population);

        assertEquals("?city dbo:populationTotal ?population .", explanation.example());
        assertTrue(explanation.advice().stream()
                .anyMatch(a -> a.contains("after Wikidata extraction")));
    }

    @Test void modelScopeSummarizesUnmappedFields() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel city = new GeneratedClassModel("City");
        city.addField("population", FieldType.NUMBER, FieldCardinality.SINGLE);
        project.rootClass(city);

        ModelElementExplanation explanation = ModelExplanationFactory.explain(
                new DecisionContext(project, null, null, null));

        assertEquals(ModelElementExplanation.Scope.MODEL, explanation.scope());
        assertTrue(explanation.advice().getFirst().contains("do not yet have"));
    }
}
