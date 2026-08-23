package wikidata.explore.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A saved model can carry an explicit {@code "canonical": null} — the store maps
 * FIELDS directly ({@code PropertyAccessor.FIELD, Visibility.ANY}, accessors off),
 * so the setter's null guard never runs on load. Real models have it: the
 * oscarnominations model stores null for Nominee and Ceremony, and generating it
 * died on {@code CanonicalSpec.isDerived()}.
 *
 * <p>The class model is therefore responsible for its own invariant — every
 * consumer (validation, canonicalization, codegen) requires the spec.
 */
class CanonicalSpecNeverNullTest {

    @Test void aPersistedNullCanonicalLoadsAsTheDefaultSpec(@TempDir Path dir)
            throws Exception {

        File file = dir.resolve("model.json").toFile();
        GeneratedProjectModelStore store = new GeneratedProjectModelStore();

        GeneratedProjectModel model = new GeneratedProjectModel();
        model.addClass(new GeneratedClassModel("Ceremony"));
        store.save(model, file);

        nullOutCanonical(file);

        GeneratedProjectModel loaded = store.load(file);
        GeneratedClassModel ceremony = loaded.classes().get(0);

        assertNotNull(ceremony.canonical(),
                "a class must always offer a canonical spec");
        assertTrue(ceremony.classKind().identityFromSource(),
                "an unspecified class is identified by its source's id");
        assertNotNull(ceremony.copy().canonical(),
                "copying must not reintroduce the null");

        // The generation path that actually failed.
        assertDoesNotThrow(() -> GeneratedProjectModelValidator.validate(loaded),
                "validation must survive a model saved without a canonical spec");
    }

    /** Rewrites every class's canonical to JSON null, as the saved models have it. */
    private static void nullOutCanonical(File file) throws Exception {
        ObjectMapper plain = new ObjectMapper();
        JsonNode root = plain.readTree(file);
        nullOutCanonical(root);
        plain.writerWithDefaultPrettyPrinter().writeValue(file, root);
    }

    private static void nullOutCanonical(JsonNode node) {
        if (node instanceof ObjectNode object && object.has("canonical")) {
            object.putNull("canonical");
        }
        node.forEach(CanonicalSpecNeverNullTest::nullOutCanonical);
    }
}
