package wikidata.explore.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A saved model file ends with a newline; the string the signature is taken over does not.
 *
 * <p>Both halves matter and they pull in opposite directions. The files are checked in,
 * so without a terminating newline git prints "\ No newline at end of file" against any
 * change touching the last line. But {@link wikidata.explore.generation.DomainSave#signature}
 * hashes {@code toJson}, so adding the same newline there would change the SHA-256 of
 * every model in the repository and make every saved domain read as stale — a
 * whole-project invalidation caused by a cosmetic edit.
 */
class ModelFileEndsWithANewlineTest {

    @Test void theSavedFileEndsWithANewlineAndTheHashedStringDoesNot(
            @TempDir Path dir) throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("Newlines");
        model.rootClass(new GeneratedClassModel("Position"));
        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        File file = dir.resolve("newlines.model.json").toFile();

        store.save(model, file);

        String written = Files.readString(file.toPath());
        assertTrue(written.endsWith("\n"),
                "a checked-in file that ends mid-line makes every later diff noisier");
        assertFalse(written.endsWith("\n\n"), "one newline, not a growing tail");
        assertFalse(store.toJson(model).endsWith("\n"),
                "the hashed string must not move: DomainSave.signature would then "
                        + "change for every model at once");
        assertTrue(written.startsWith(store.toJson(model)),
                "and the file is that same string, plus the terminator");
    }

    /** Saving twice writes the same bytes: the terminator does not accumulate. */
    @Test void savingTwiceWritesTheSameBytes(@TempDir Path dir) throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("Newlines");
        model.rootClass(new GeneratedClassModel("Position"));
        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        File file = dir.resolve("newlines.model.json").toFile();

        store.save(model, file);
        String first = Files.readString(file.toPath());
        store.save(model, file);

        org.junit.jupiter.api.Assertions.assertEquals(
                first, Files.readString(file.toPath()));
    }
}
