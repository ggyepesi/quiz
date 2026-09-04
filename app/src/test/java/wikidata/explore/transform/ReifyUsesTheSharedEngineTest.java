package wikidata.explore.transform;

import canonical.KeyComponent;
import canonical.Reduction;
import org.junit.jupiter.api.Test;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.model.GeneratedProjectModelStore;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reify path reduces through the shared engine, on the real models.
 *
 * <p>It had its own merge: union every collection, fill every empty scalar, silently keep
 * the first of two conflicting ones — one rule for every class, which is why "union the
 * laureates while requiring the category to agree" could not be said. The reducers say it
 * now, and they arrive from the compiled class rather than being written per call site.
 */
class ReifyUsesTheSharedEngineTest {

    private static Map<String, Reduction> reducersOf(String domain, String type)
            throws Exception {
        var model = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/" + domain + "/" + domain + ".model.json"));
        for (var reification : ModelStatementReifications.derive(
                ProjectModelCompiler.compile(model))) {
            if (type.equals(reification.reify().targetType())) {
                return reification.reify().plan().reductionByField();
            }
        }
        throw new AssertionError("no reification for " + type);
    }

    /**
     * The design's acceptance criterion, arriving at the transform that will apply it —
     * and nobody configured it. Category, year and motivation are the key, so they are
     * not reduced; laureates is a collection, so it unions.
     */
    @Test void nobelReachesTheTransformWithTheUnionItNeeds() throws Exception {
        assertEquals(Map.of("laureates", Reduction.UNION_DISTINCT),
                reducersOf("nobelprizes", "LaureatesWithMotivation"));
    }

    /** A key component is never reduced: its value formed the partition. */
    @Test void oscarsReducesOnlyWhatIsNotItsKey() throws Exception {
        var reducers = reducersOf("oscarnominations", "Nomination");

        assertEquals(Map.of("won", Reduction.REQUIRE_AGREEMENT), reducers);
        assertTrue(reducers.keySet().stream().noneMatch(
                        List.of("category", "forWork", "nominee", "ceremony")::contains),
                "the key made the partition, so every candidate in it already agrees");
    }

    /** And the plan the transform reads is the one the model authored. */
    @Test void theKeyReachingTheTransformIsTheAuthoredOne() throws Exception {
        var model = new GeneratedProjectModelStore().load(new File(
                "../data/wikidata/nobelprizes/nobelprizes.model.json"));
        for (var reification : ModelStatementReifications.derive(
                ProjectModelCompiler.compile(model))) {
            if (!"LaureatesWithMotivation".equals(reification.reify().targetType())) continue;
            assertEquals(
                    List.of(KeyComponent.field("category"), KeyComponent.field("year"),
                            KeyComponent.field("motivation")),
                    reification.reify().plan().key());
            return;
        }
        throw new AssertionError("no reification found");
    }
}
