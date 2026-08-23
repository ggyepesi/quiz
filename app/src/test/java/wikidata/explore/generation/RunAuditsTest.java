package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Accepting a run closes its results window, and with it every rule bucket — the only
 * account of what the run did. The run itself still holds all of it, so what is lost is
 * the view, not the data. This is that account in words, put somewhere that persists.
 */
class RunAuditsTest {

    private static WikidataDynamicObject obj(String id) {
        WikidataDynamicObject o = new WikidataDynamicObject(id, id);
        o.type("Nomination");
        return o;
    }

    private static GenerationRun run(GenerationRun.SelfReferenceAudit selfReference,
                                     GenerationRun.OwnedCompositionAudit owned,
                                     GenerationRun.KindClassificationAudit kinds) {
        return run(selfReference, owned, kinds, GenerationRun.ProjectionAudit.notRun());
    }

    private static GenerationRun run(GenerationRun.SelfReferenceAudit selfReference,
                                     GenerationRun.OwnedCompositionAudit owned,
                                     GenerationRun.KindClassificationAudit kinds,
                                     GenerationRun.ProjectionAudit projections) {
        return new GenerationRun(new GeneratedProjectModel(), 1, null,
                List.of(obj("n1")), null, List.of(), null, List.of(),
                GenerationRun.Quality.completeQuality(), List.of(),
                selfReference, owned, kinds, projections);
    }

    @Test void aCleanRunStillSaysWhatEachRuleDid() {
        // Silence in the buckets is the healthy outcome, and it must not read as
        // silence about whether the rules ran at all.
        String report = RunAudits.report(run(
                GenerationRun.SelfReferenceAudit.ran(List.of()),
                GenerationRun.OwnedCompositionAudit.ran(List.of()),
                GenerationRun.KindClassificationAudit.ran(List.of())));

        assertTrue(report.contains("Self-reference: Ran; no self-reference decisions"),
                report);
        assertTrue(report.contains("every owned part already existed"), report);
        assertTrue(report.contains("every kind was already settled"), report);
    }

    @Test void aRuleThatNeverRanSaysSoRatherThanLookingClean() {
        String report = RunAudits.report(run(
                GenerationRun.SelfReferenceAudit.notRun(),
                GenerationRun.OwnedCompositionAudit.ran(List.of()),
                GenerationRun.KindClassificationAudit.ran(List.of())));

        assertTrue(report.contains("Self-reference: Not run in this operation"), report);
    }

    @Test void whatTheRulesFoundIsReportedAlongsideTheirStatus() {
        String report = RunAudits.report(run(
                GenerationRun.SelfReferenceAudit.ran(List.of()),
                GenerationRun.OwnedCompositionAudit.ran(List.of(obj("part"))),
                GenerationRun.KindClassificationAudit.ran(List.of())));

        assertTrue(report.contains("Owned parts newly created"), report);
        assertTrue(report.contains("1 part(s) newly created"), report);
    }

    @Test void thereIsAlwaysAnAccountEvenWithNoRun() {
        assertEquals("No run to report.", RunAudits.report(null));
    }

    @Test void anAuditSurvivesRebuildingTheRunAroundIt() {
        // forgetFetchedDeclaration rebuilds the run to change what the next enrich will
        // fetch. That does not un-run the rules this run already ran, and dropping their
        // audits would report that nothing had been evaluated.
        GenerationRun original = run(
                GenerationRun.SelfReferenceAudit.ran(List.of()),
                GenerationRun.OwnedCompositionAudit.ran(List.of(obj("part"))),
                GenerationRun.KindClassificationAudit.ran(List.of(obj("kind"))),
                GenerationRun.ProjectionAudit.notRun());

        GenerationRun rebuilt = new GenerationRun(
                original.modelSnapshot(), original.depth(), original.plan(),
                original.dynamicObjects(), original.runtime(), original.instances(),
                original.remapState(), List.of(), original.quality(),
                original.fieldCoverage(), original.selfReferenceAudit(),
                original.ownedCompositionAudit(), original.kindClassificationAudit(),
                original.projectionAudit());

        assertEquals(RunAudits.report(original), RunAudits.report(rebuilt));
    }
}
