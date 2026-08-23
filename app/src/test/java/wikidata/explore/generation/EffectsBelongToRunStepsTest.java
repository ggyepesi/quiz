package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import process.ProcessWorkflowPipeline;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pipeline is already the one component plan, execution and results share, and its
 * phases are already the steps. So a rule bucket says which step produced it — and the
 * result can then be read as the plan rather than as a list beside it.
 *
 * <p>It also decides where the account survives. Result tabs die when the run is
 * accepted; a phase summary is run state, saved with the artifact and reopened by the
 * Run Inspector. Attaching effects to phases is what carries them across that line.
 */
class EffectsBelongToRunStepsTest {

    private static WikidataDynamicObject obj(String id) {
        WikidataDynamicObject o = new WikidataDynamicObject(id, id);
        o.type("Nomination");
        return o;
    }

    private static List<RuleEffects.Effect> everyRule() {
        return RuleEffects.fromRun(
                List.of(new wikidata.explore.transform.FieldExpectations.FieldCoverage(
                        "Nomination", "ceremony",
                        wikidata.explore.model.FieldExpectation.EXPECTED, 10, 8,
                        List.of(obj("n1"), obj("n2")))),
                GenerationRun.SelfReferenceAudit.ran(List.of(
                        new wikidata.explore.transform.TransformEngine.SelfRefFinding(
                                wikidata.explore.transform.TransformEngine
                                        .SelfRefDecision.DROPPED,
                                obj("whale"), obj("witness"), List.of("category"), "why"))),
                GenerationRun.OwnedCompositionAudit.ran(List.of(obj("part"))),
                GenerationRun.KindClassificationAudit.ran(List.of(obj("kind"))),
                GenerationRun.ProjectionAudit.notRun());
    }

    @Test void eachRuleNamesTheStepThatRanIt() {
        assertEquals(RuleEffects.RunPhase.CONSTRUCT,
                RuleEffects.inPhase(everyRule(), RuleEffects.RunPhase.CONSTRUCT)
                        .getFirst().phase());
        assertEquals(2, RuleEffects.inPhase(
                        everyRule(), RuleEffects.RunPhase.SEMANTIC).size(),
                "owned parts and kinds are both settled in the semantic step");
        assertEquals(1, RuleEffects.inPhase(
                        everyRule(), RuleEffects.RunPhase.FINALIZE).size(),
                "expectations are checked at the end, on the finished pool");
    }

    @Test void theEffectsComeBackInTheOrderTheRunProducedThem() {
        List<RuleEffects.RunPhase> order = everyRule().stream()
                .map(RuleEffects.Effect::phase).distinct().toList();

        assertEquals(List.of(RuleEffects.RunPhase.CONSTRUCT,
                        RuleEffects.RunPhase.SEMANTIC, RuleEffects.RunPhase.FINALIZE),
                order, "so the result reads top to bottom as the plan did");
    }

    @Test void aStepsSummaryGainsWhatItAccountedForWithoutLosingWhatItProduced() {
        // Both halves of the sentence matter: the executor says what the step made,
        // the rules say what they did with it.
        ProcessWorkflowPipeline pipeline = new ProcessWorkflowPipeline(List.of(
                new ProcessWorkflowPipeline.Phase(GenerateDomainPipeline.CONSTRUCT,
                        "Construct", "", List.of())));
        pipeline.start(GenerateDomainPipeline.CONSTRUCT, "");
        pipeline.complete(GenerateDomainPipeline.CONSTRUCT, "14904 statement record(s)");

        RunPhaseSummaries.record(pipeline, everyRule());

        String summary = pipeline.snapshot().getFirst().summary();
        assertTrue(summary.startsWith("14904 statement record(s)"),
                "what the step produced is not overwritten: " + summary);
        assertTrue(summary.contains("Self-referential"),
                "and what its rules accounted for is added: " + summary);
    }

    @Test void anOperationWhoseStepDoesNotExistIsNotGivenOne() {
        // Remap and Enrich run as a single step. Inventing a phase so the text has
        // somewhere to go would put a stage in the plan that never ran.
        ProcessWorkflowPipeline single = new ProcessWorkflowPipeline(List.of(
                new ProcessWorkflowPipeline.Phase("remap", "Remap locally", "", List.of())));
        single.start("remap", "");
        single.complete("remap", "40173 objects");

        RunPhaseSummaries.record(single, everyRule());

        assertEquals(1, single.snapshot().size());
        assertEquals("40173 objects", single.snapshot().getFirst().summary());
    }
}
