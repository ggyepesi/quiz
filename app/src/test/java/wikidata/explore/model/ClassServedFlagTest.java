package wikidata.explore.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-class {@code served} flag (production class vs load-only backbone).
 * Must default to true so every pre-existing model is unchanged, and round-trip
 * through the store so a domain can opt a backbone class out of serving.
 */
class ClassServedFlagTest {

    @Test void defaultsToServed() {
        assertTrue(new GeneratedClassModel("Nomination").served(),
                "a class is a served product by default");
    }

    @Test void servedFalseRoundTripsThroughTheStore(@TempDir Path dir) throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass().className("Nomination");          // served product
        GeneratedClassModel backbone = new GeneratedClassModel("OscarNominations");
        backbone.served(false);                             // load-only backbone
        model.addClass(backbone);

        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        File f = dir.resolve("m.model.json").toFile();
        store.save(model, f);
        GeneratedProjectModel loaded = store.load(f);

        GeneratedClassModel reloaded = loaded.classes().stream()
                .filter(c -> c.className().equals("OscarNominations"))
                .findFirst().orElseThrow();
        assertFalse(reloaded.served(), "served=false persists");
        assertTrue(loaded.rootClass().served(), "the product class stays served");
    }

    @Test void absentFieldDefaultsToServed(@TempDir Path dir) throws Exception {
        // A model saved by an older version has no "served" key; it must read back
        // as served=true. Simulate by stripping the field from a saved model.
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass().className("Nomination");
        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        File f = dir.resolve("legacy.model.json").toFile();
        store.save(model, f);

        String json = Files.readString(f.toPath())
                .replaceAll("\"served\"\\s*:\\s*(true|false)\\s*,", "");
        Files.writeString(f.toPath(), json);

        GeneratedProjectModel loaded = store.load(f);
        assertTrue(loaded.rootClass().served(),
                "absent 'served' is backward-compatibly true");
    }
}
