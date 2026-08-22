package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #93: after restarting ModelBuilder and loading a saved dataset, Remap appeared to
 * succeed and changed nothing — {@code Nomination.year} stayed at 509 missing though the
 * projection was configured. The pipeline had two branches and said which one it took
 * only in passing; nothing told the user that reify, inverts and companion match were
 * not going to re-run at all. What a Remap can apply is now asked as a question, before
 * it runs, and the answer names what it will skip.
 */
class RemapScopeTest {

    private static GenerationRun run(GenerationRun.RemapState state) {
        return new GenerationRun(null, 1, null,
                List.of(new WikidataDynamicObject("Q1", "One")), null, List.of(), state);
    }

    @Test void aRunGeneratedThisSessionCanReplayEverything() {
        RemapScope scope = RemapScope.of(run(new GenerationRun.RemapState(
                List.of(new WikidataDynamicObject("Q1", "One")), Map.of())));

        assertTrue(scope.retransform());
        assertTrue(scope.skippedStages().isEmpty());
        assertEquals("", scope.limitation(),
                "nothing is skipped, so there is nothing to warn about");
    }

    @Test void aLoadedRunCannotReplayTheStagesThatNeedThePreReificationPool() {
        RemapScope scope = RemapScope.of(run(null));

        assertFalse(scope.retransform());
        assertTrue(scope.skippedStages().containsAll(
                        List.of("reify", "companion match")),
                "the stages that would double-apply are named: " + scope.skippedStages());
        assertFalse(scope.skippedStages().contains("inverts"),
                "the idempotent snapshot path does replay inverts");
    }

    @Test void theLimitationSaysWhatWillNotBeAppliedAndWhatToDoInstead() {
        String limitation = RemapScope.of(run(null)).limitation();

        assertTrue(limitation.contains("reify"), limitation);
        assertTrue(limitation.contains("will NOT be applied"), limitation);
        assertTrue(limitation.contains("Generate"),
                "and it names the action that WOULD apply them: " + limitation);
    }

    @Test void anEmptyEnrichedPoolIsNoPoolAtAll() {
        // The distinction is "can I replay from pre-reification objects", not
        // "is the field non-null" — an empty cache replays nothing.
        RemapScope scope = RemapScope.of(
                run(new GenerationRun.RemapState(List.of(), Map.of())));

        assertFalse(scope.retransform());
    }

    @Test void aMissingRunIsTreatedAsHavingNothingToReplayFrom() {
        assertFalse(RemapScope.of(null).retransform());
    }

    @Test void thePlanDescriptionDistinguishesTheTwoRemaps() {
        String full = RemapScope.of(run(new GenerationRun.RemapState(
                List.of(new WikidataDynamicObject("Q1", "One")), Map.of()))).describe();
        String partial = RemapScope.of(run(null)).describe();

        assertTrue(full.contains("full transform sequence"), full);
        assertTrue(partial.contains("idempotent"), partial);
        assertFalse(full.equals(partial),
                "the two Remaps must not read the same in the workflow plan");
    }
}
