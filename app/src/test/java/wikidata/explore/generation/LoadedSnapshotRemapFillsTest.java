package wikidata.explore.generation;

import aux.FlexibleDate;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.transform.ModelYearProjections;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #93, the reported symptom: after an app restart, loading a saved dataset and hitting
 * Remap left {@code Nomination.year} at 509 missing even though {@code year ←
 * edition.date.year} was configured. The pool from a loaded snapshot is already reified,
 * so the reify/invert stages cannot replay — but a projection added since the snapshot
 * was saved is idempotent and overwrite-only, so it MUST still fill. That is what makes
 * the narrower Remap worth running at all rather than a no-op with a friendly message.
 */
class LoadedSnapshotRemapFillsTest {

    private static WikidataDynamicObject obj(String id, String name, String type) {
        WikidataDynamicObject o = new WikidataDynamicObject(id, name);
        o.type(type);
        return o;
    }

    /** A model whose Nomination.year is projected from edition.date.year. */
    private static GeneratedProjectModel modelWithYearProjection() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("oscars");
        project.rootClass(new GeneratedClassModel("Root"));
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        GeneratedFieldModel year =
                nomination.addField("year", FieldType.DATE, FieldCardinality.SINGLE);
        year.mapping().subjectField("edition");
        year.mapping().matchValueField("date.year");
        project.addClass(nomination);
        return project;
    }

    /** The pool as a LOADED snapshot holds it: already-reified Nominations pointing at
     *  dated Edition entities, with year not yet projected. */
    private static List<WikidataDynamicObject> loadedPool() {
        WikidataDynamicObject edition =
                obj("Q66707607", "95th Academy Awards", "Edition");
        edition.put("date", new FlexibleDate(2023));

        WikidataDynamicObject nomination =
                obj("Q38195662$real", "Hong Chau", "Nomination");
        nomination.put("edition", edition);

        WikidataDynamicObject undated = obj("Q1$bare", "The Whale", "Nomination");

        return new ArrayList<>(List.of(edition, nomination, undated));
    }

    @Test void aProjectionAddedAfterTheSaveStillFillsOnRemap() {
        List<WikidataDynamicObject> pool = loadedPool();
        assertNull(pool.get(1).get("year"), "precondition: the year is missing");

        int filled = ModelYearProjections.apply(modelWithYearProjection(), pool, null);

        assertEquals(1, filled, "the one Nomination with a dated edition fills");
        assertNotNull(pool.get(1).get("year"),
                "this is the 509-missing symptom: it must no longer stay empty");
    }

    @Test void aNominationWithNoEditionIsLeftAloneRatherThanGuessed() {
        List<WikidataDynamicObject> pool = loadedPool();

        ModelYearProjections.apply(modelWithYearProjection(), pool, null);

        assertNull(pool.get(2).get("year"),
                "no edition means no year — honest incompleteness, not a fabricated one");
    }

    @Test void reRunningTheProjectionChangesNothingMore() {
        // Why this stage is safe on an already-reified pool at all: it is idempotent,
        // unlike reify, which would build a second copy of every atom.
        List<WikidataDynamicObject> pool = loadedPool();
        GeneratedProjectModel model = modelWithYearProjection();

        int first = ModelYearProjections.apply(model, pool, null);
        int second = ModelYearProjections.apply(model, pool, null);
        int poolSize = pool.size();

        assertEquals(1, first);
        assertEquals(0, second, "already filled, so nothing changes");
        assertEquals(3, poolSize, "and no object is duplicated");
    }

    @Test void loadedSnapshotRemapCompletesEveryStepItActuallyRuns() throws Exception {
        GeneratedProjectModel model = modelWithYearProjection();
        GenerationRun previous = new GenerationRun(
                model, 1, null, loadedPool(), null, List.of());
        process.ProcessWorkflowPipeline pipeline =
                GenerateDomainPipeline.configuredRemap(List.of(), false);

        new GenerationPipeline().remap(
                previous, model, null, RunSteps.of(pipeline));

        assertTrue(pipeline.snapshot().stream().allMatch(
                        state -> state.status()
                                == process.ProcessWorkflowPipeline.Status.COMPLETED),
                pipeline.snapshot().toString());
        assertTrue(pipeline.snapshot().stream().allMatch(
                        state -> state.startedAtNanos() > 0),
                "every completed phase must have been started before its work");
    }
}
