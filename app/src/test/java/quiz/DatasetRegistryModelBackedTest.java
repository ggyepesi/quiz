package quiz;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dataset is MODEL-backed when it was built from a ModelBuilder domain, and is
 * not when it was saved straight from a TransformApp working set — that save
 * leaves the model fields blank on purpose ("a transform-view save is not
 * model-backed", {@code DomainSaver}).
 *
 * <p>ModelBuilder lists only the model-backed ones: it can neither reopen nor
 * regenerate the others, and offering them meant switching to a model file that
 * does not exist.
 */
class DatasetRegistryModelBackedTest {

    @Test void aModelBuilderDomainIsModelBacked() {
        DatasetRegistry.Dataset d = new DatasetRegistry.Dataset();
        d.name("Periodic Table");
        d.modelPath("data/wikidata/periodictable/periodictable.model.json");
        d.ruletreePath("data/wikidata/periodictable/periodictable.ruletree.json");
        d.snapshotPath("data/wikidata/periodictable/periodictable.snapshot.json");

        assertTrue(d.isModelBacked());
    }

    @Test void aTransformAppSaveIsNot() {
        // Exactly what DomainSaver writes: a snapshot and its types, no model.
        DatasetRegistry.Dataset d = new DatasetRegistry.Dataset();
        d.name("countries");
        d.snapshotPath("data/wikidata/transform/countries.snapshot.json");
        d.instanceCount(768);

        assertFalse(d.isModelBacked(),
                "a snapshot saved from a working set has no model to reopen");
        assertFalse(new DatasetRegistry.Dataset().isModelBacked(),
                "an empty dataset is not model-backed either");
    }

    @Test void aBlankModelPathIsNotAPath() {
        DatasetRegistry.Dataset d = new DatasetRegistry.Dataset();
        d.modelPath("   ");

        assertFalse(d.isModelBacked(),
                "whitespace is not a model path — the registry normalises to blank");
    }
}
