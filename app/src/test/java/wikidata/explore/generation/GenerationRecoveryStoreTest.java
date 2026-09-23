package wikidata.explore.generation;

import dataset.DomainStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The expensive final graph survives materialization failure, but not model drift. */
class GenerationRecoveryStoreTest {

    @Test void aMatchingModelCanResumeItsFinalGraphAndSuccessClearsIt(@TempDir Path root)
            throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("History");
        WikidataDynamicObject position = new WikidataDynamicObject("Q1", "Position");
        position.type(model.rootClass().className());
        GenerationRun.Quality quality = GenerationRun.Quality.partial(
                List.of("one unavailable label"), List.of("Q2"));
        GraphCheckpoint checkpoint = GraphCheckpoint.finalGraph(
                List.of(position), List.of(), datasource.graph.GraphDiscoveryState.EMPTY,
                quality, DomainSave.signature(model));

        GenerationRecoveryStore store = GenerationRecoveryStore.in(
                DomainStorage.in(root.toFile()), model.name());
        store.save(model, checkpoint,
                wikidata.explore.transform.SelfReferenceLedger.EMPTY);

        assertTrue(store.canResume(model));
        GenerationRecoveryStore.Recovered recovered = store.load(model);
        assertEquals(List.of("Q1"), recovered.snapshot().objects().stream()
                .map(WikidataDynamicObject::qid).toList());
        assertEquals(quality, recovered.quality());

        GeneratedProjectModel edited = model.copy();
        edited.rootClass().className("DifferentPosition");
        assertFalse(store.canResume(edited));

        store.clear();
        assertFalse(store.canResume(model));
    }
}
