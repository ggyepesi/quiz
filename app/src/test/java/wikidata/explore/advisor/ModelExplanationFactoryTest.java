package wikidata.explore.advisor;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldSourceType;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.RuleDirection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /** A primary infobox source is now selectable, so the advisor has to describe how it
     *  is really filled. It used to render a Wikidata triple pattern, telling the reader
     *  a SPARQL query would ask for a value no SPARQL query ever asks about. */
    @Test void explainsAPrimaryInfoboxSourceAsWhatItActuallyReads() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel movie = new GeneratedClassModel("Movie");
        GeneratedFieldModel country = movie.addField(
                "country", FieldType.TEXT, FieldCardinality.SINGLE);
        country.mapping().sourceType(FieldSourceType.WIKIPEDIA_INFOBOX);
        country.mapping().propertyPid("Infobox film.country");
        project.rootClass(movie);

        ModelElementExplanation explanation =
                ModelExplanationFactory.explainField(project, movie, country);

        assertEquals("?movie enwiki {{Infobox film}} | country = ?country",
                explanation.example());
        assertFalse(explanation.example().contains("wdt:"), "it is not a Wikidata triple");
        assertTrue(explanation.advice().stream()
                .anyMatch(a -> a.contains("after Wikidata extraction")));
        assertTrue(explanation.advice().stream()
                .noneMatch(a -> a.contains("not implemented yet")));
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
