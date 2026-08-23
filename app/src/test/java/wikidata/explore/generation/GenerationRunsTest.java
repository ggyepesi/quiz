package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.explore.codegen.GeneratedViewableRuntime;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.transform.TransformEngine;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which compiled runtime is finished with when one run replaces another.
 *
 * <p>A run owns class loaders for the classes it generated, so replacing it has to close them.
 * Except when the incoming run is the outgoing one with a detail changed and carries the SAME
 * runtime — forgetting a fetched declaration rebuilds the run that way — where closing shuts
 * loaders still in use. Both halves were already true in the frame, in two places, neither
 * stating the rule: one closed, the other pointedly did not, and a third caller had nothing to
 * consult.
 */
class GenerationRunsTest {

    @Test void aRunWithItsOwnRuntimeSupersedesTheOneBefore() {
        GeneratedViewableRuntime finished = runtime("first");
        GenerationRun previous = run(finished);

        assertSame(finished, GenerationRuns.superseded(previous, run(runtime("second"))));
    }

    /** The forgetFetchedDeclaration case: same run, one detail changed, same runtime. */
    @Test void aRebuiltRunCarryingTheSameRuntimeSupersedesNothing() {
        GeneratedViewableRuntime shared = runtime("shared");
        GenerationRun previous = run(shared);

        assertNull(GenerationRuns.superseded(previous, run(shared)),
                "closing it would shut loaders the incoming run still needs");
    }

    @Test void clearingTheRunFinishesWithItsRuntime() {
        GeneratedViewableRuntime finished = runtime("first");

        assertSame(finished, GenerationRuns.superseded(run(finished), null));
    }

    @Test void handingARunToItselfFinishesWithNothing() {
        GenerationRun only = run(runtime("only"));

        assertNull(GenerationRuns.superseded(only, only));
    }

    @Test void thereIsNothingToCloseBeforeTheFirstRun() {
        assertNull(GenerationRuns.superseded(null, run(runtime("first"))));
        assertNull(GenerationRuns.superseded(null, null));
        assertNull(GenerationRuns.superseded(run(null), run(runtime("next"))),
                "a run that compiled nothing has no loaders to shut");
    }

    @Test void handOverReturnsWhateverTakesOver() {
        GenerationRun next = run(runtime("next"));

        assertSame(next, GenerationRuns.handOver(run(runtime("previous")), next));
        assertNull(GenerationRuns.handOver(run(runtime("previous")), null));
    }

    @Test void handOverClosesTheRuntimeThatIsNoLongerCarried() {
        AtomicInteger closes = new AtomicInteger();
        GeneratedViewableRuntime previous = runtime("previous", closes);

        GenerationRuns.handOver(run(previous), run(runtime("next")));

        org.junit.jupiter.api.Assertions.assertEquals(1, closes.get());
    }

    @Test void handOverDoesNotCloseARuntimeCarriedByTheReplacement() {
        AtomicInteger closes = new AtomicInteger();
        GeneratedViewableRuntime shared = runtime("shared", closes);

        GenerationRuns.handOver(run(shared), run(shared));

        org.junit.jupiter.api.Assertions.assertEquals(0, closes.get());
    }

    @Test void aDisplayOnlyRunSaysTheSelfReferenceRuleDidNotRun() {
        GenerationRun run = run(runtime("display-only"));

        assertFalse(run.selfReferenceAudit().executed());
        assertTrue(run.selfReferenceFindings().isEmpty());
        assertTrue(run.selfReferenceAudit().description().contains("Not run"));
    }

    @Test void anExecutedRuleWithNoDecisionsIsNotConfusedWithNotRunning() {
        GenerationRun run = new GenerationRun(null, 0, null, List.of(), runtime("full"),
                List.of(), null, List.of(), GenerationRun.Quality.completeQuality(),
                List.of(), List.of());

        assertTrue(run.selfReferenceAudit().executed());
        assertTrue(run.selfReferenceAudit().description().contains("no self-reference"));
    }

    @Test void aTransformDecisionSurvivesOnTheRunAsACompleteAuditRecord() {
        WikidataDynamicObject atom = new WikidataDynamicObject("atom", "Atom");
        WikidataDynamicObject witness = new WikidataDynamicObject("witness", "Witness");
        TransformEngine.SelfRefFinding finding = new TransformEngine.SelfRefFinding(
                TransformEngine.SelfRefDecision.DROPPED, atom, witness,
                List.of("category", "ceremony"), "witnessed duplicate");

        GenerationRun run = new GenerationRun(null, 0, null, List.of(), runtime("full"),
                List.of(), null, List.of(), GenerationRun.Quality.completeQuality(),
                List.of(), List.of(finding));

        assertTrue(run.selfReferenceAudit().executed());
        assertSame(finding, run.selfReferenceAudit().findings().getFirst());
        assertSame(witness, run.selfReferenceFindings().getFirst().witness());
    }

    private static GeneratedViewableRuntime runtime(String name) {
        // A record with no loaders: close() is a no-op, and identity is what the rule turns on.
        return new GeneratedViewableRuntime(null, name, "", null, null, Map.of());
    }

    private static GeneratedViewableRuntime runtime(String name, AtomicInteger closes) {
        URLClassLoader loader = new URLClassLoader(new URL[0], null) {
            @Override public void close() throws IOException {
                closes.incrementAndGet();
                super.close();
            }
        };
        // A null byType represents a single-loader runtime and exercises closeLoader directly.
        return new GeneratedViewableRuntime(null, name, "", null, loader, null);
    }

    private static GenerationRun run(GeneratedViewableRuntime runtime) {
        return new GenerationRun(null, 0, null, List.of(), runtime, List.of());
    }
}
