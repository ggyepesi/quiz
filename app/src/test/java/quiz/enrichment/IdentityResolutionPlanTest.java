package quiz.enrichment;

import org.junit.jupiter.api.Test;
import quiz.transform.DynamicViewable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IdentityResolutionPlanTest {

    @Test void separatesExistingIdentitiesFromWorkBeforeExecution() {
        DynamicViewable nativeMovie = new DynamicViewable("Q1", "Movie");
        DynamicViewable local = new DynamicViewable("local-2", "Local movie");

        IdentityResolutionPlan plan = IdentityResolutionPlan.inspect(
                "Movies", List.of(nativeMovie, local),
                member -> member.getIdentifier().startsWith("Q")
                        ? member.getIdentifier() : null);

        assertEquals(1, plan.identified().size());
        assertEquals("Q1", plan.identified().get(0).currentQid());
        assertEquals(List.of(local),
                plan.unresolved().stream().map(IdentityResolutionPlan.Entry::member).toList());
    }

    @Test void aNativeWikidataMovieScopeProducesNoSearchWork() {
        List<DynamicViewable> movies = java.util.stream.IntStream.rangeClosed(1, 20_000)
                .mapToObj(i -> new DynamicViewable("Q" + i, "Movie " + i)).toList();

        IdentityResolutionPlan plan = IdentityResolutionPlan.inspect(
                "Movies", movies, member -> member.getIdentifier());

        assertEquals(20_000, plan.identified().size());
        assertEquals(0, plan.unresolved().size());
    }
}
