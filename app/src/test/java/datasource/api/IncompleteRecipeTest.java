package datasource.api;

import datasource.Datasources;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a recipe still being filled in means, agreed across every family.
 *
 * <p>Each operation used to answer differently: a category recipe was described as
 * incomplete while an infobox or DBpedia one threw. Every operation compiles a plan, so
 * a throw took Generate, Enrich and Remap down at their first line with a stack trace —
 * for a field the run could simply have left alone and said so.
 *
 * <p>A MISSING parameter never gets this far: the recipe refuses to resolve, naming the
 * parameter. What reaches prepare is a recipe that resolves and still says nothing the
 * family can execute — a pattern with no placeholder, or a recipe sitting in a slot its
 * operation does not serve, which is what a migrated or hand-edited model can hold.
 */
class IncompleteRecipeTest {

    @Test void everyFamilyDescribesAnUnfinishedRecipeInsteadOfRefusingThePlan() {
        SourceExecutionPlan plan = SourceExecutionPlan.compile(List.of(
                        // An infobox recipe in the category slot: resolves, and names
                        // no infobox field, because that slot is not one it serves.
                        field("Movie", "country", SourceBindingSlot.CATEGORY_EVIDENCE,
                                "wikipedia", "infobox-parameter",
                                Map.of("property", "Infobox film.country")),
                        field("Movie", "director", SourceBindingSlot.CATEGORY_EVIDENCE,
                                "dbpedia", "property", Map.of("property", "director")),
                        field("Movie", "location", SourceBindingSlot.CATEGORY_EVIDENCE,
                                "wikipedia", "category",
                                Map.of("pattern", "Films set in"))),
                Datasources.standard());

        assertEquals(3, plan.steps().size(), "the plan compiles rather than refusing");
        for (SourceExecutionPlan.Step step : plan.steps()) {
            PreparedSourceOperation prepared = step.prepared();
            assertEquals(PreparedSourceOperation.Execution.RETAIN, prepared.execution(),
                    "nothing is acquired for it: " + prepared.description());
            assertTrue(prepared.description().startsWith("Incomplete"),
                    "and the run says which recipe: " + prepared.description());
            assertNull(prepared.configuration(),
                    "there is no configuration to carry: " + prepared.description());
        }
        assertEquals(0, plan.selfAcquiring(),
                "so the headline does not promise work that will not happen");
        // And the gate every family now shares agrees: RETAIN is what makes one
        // general question equivalent to the bespoke ones it replaced.
        assertFalse(plan.acquires("wikipedia-infobox-field"));
        assertFalse(plan.acquires("wikipedia-category-field"));
        assertFalse(plan.acquires("dbpedia-field"));
    }

    private static SourceBinding field(String owner, String path, SourceBindingSlot slot,
            String provider, String operation, Map<String, String> parameters) {
        return new SourceBinding(SourceBindingTarget.fieldValue(owner, path, slot),
                new SourceRecipe(provider, operation, parameters));
    }
}
