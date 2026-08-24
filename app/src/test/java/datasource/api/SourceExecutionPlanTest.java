package datasource.api;

import datasource.Datasources;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SourceExecutionPlanTest {

    @Test void resolvesBindingsOnceAndIndexesThemByTypedTarget() {
        SourceBinding population = new SourceBinding(
                SourceBindingTarget.classPopulation("Movie"),
                new SourceRecipe("wikidata", "statement-membership", Map.of(
                        "property", "P31", "values", "Q11424")));
        SourceBinding label = new SourceBinding(
                SourceBindingTarget.classNames("Movie", SourceBindingSlot.CLASS_LABEL),
                new SourceRecipe("wikidata", "label", Map.of()));

        SourceExecutionPlan plan = SourceExecutionPlan.compile(
                List.of(population, label), Datasources.standard());

        assertEquals(2, plan.steps().size());
        assertEquals(1, plan.selfAcquiring(), "counts what CAN acquire, not what ran");
        assertEquals(1, plan.declarations());
        assertSame(population, plan.step(population.target()).binding());
        assertEquals(List.of(population), plan.steps(BindingScope.CLASS_POPULATION)
                .stream().map(SourceExecutionPlan.Step::binding).toList());
    }

    @Test void twoRecipesCannotOccupyOneExecutionSlot() {
        SourceBindingTarget target = SourceBindingTarget.fieldValue(
                "Movie", "country", SourceBindingSlot.FALLBACK_FIELD_VALUE);
        SourceBinding wikipedia = new SourceBinding(target,
                new SourceRecipe("wikipedia", "infobox-parameter",
                        Map.of("property", "Infobox film.country")));
        SourceBinding dbpedia = new SourceBinding(target,
                new SourceRecipe("dbpedia", "property", Map.of("property", "country")));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SourceExecutionPlan.compile(
                        List.of(wikipedia, dbpedia), Datasources.standard()));

        assertTrue(failure.getMessage().contains("Movie.country"));
        assertTrue(failure.getMessage().contains("additional-source"));
    }

    @Test void aDocumentCannotBePlannedAsAFieldValue() {
        SourceBinding invalid = new SourceBinding(
                SourceBindingTarget.fieldValue(
                        "Movie", "plot", SourceBindingSlot.FALLBACK_FIELD_VALUE),
                new SourceRecipe("wikipedia", "article", Map.of()));

        assertThrows(IllegalArgumentException.class,
                () -> SourceExecutionPlan.compile(List.of(invalid), Datasources.standard()));
    }
}
