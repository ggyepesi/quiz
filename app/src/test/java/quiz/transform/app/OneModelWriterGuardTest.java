package quiz.transform.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TransformApp writes a ModelBuilder model in exactly one place.
 *
 * <p>Three paths used to write model.json: Save, Save population selection and Promote
 * to model, the last two each reading the file, editing it and writing it back on the
 * spot. That made Save one persistence boundary among three, and let a promotion
 * overwrite whatever ModelBuilder had saved in between (#305). Every model edit now lands
 * in the opened domain's working model, and {@code CuratableDomain.writeProjectModel} —
 * called by Save — is the only writer. A new write anywhere else fails here.
 */
class OneModelWriterGuardTest {

    /** A file that uses the model store and calls save on anything counts as a writer.
     *  Matching the call through a variable too: the promoter wrote via store.save(...). */
    private static final Pattern SAVE_CALL = Pattern.compile("\\.save\\(");

    @Test void onlyTheWorkingModelWriterSavesAModel() throws Exception {
        List<String> writers;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/quiz"))) {
            writers = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> {
                        try {
                            String source = Files.readString(file);
                            return source.contains("GeneratedProjectModelStore")
                                    && SAVE_CALL.matcher(source).find();
                        } catch (java.io.IOException unreadable) {
                            throw new java.io.UncheckedIOException(unreadable);
                        }
                    })
                    .map(file -> file.getFileName().toString())
                    .sorted().toList();
        }

        assertEquals(List.of("CuratableDomain.java"), writers,
                "a TransformApp model edit belongs in the working model; only Save, through "
                        + "CuratableDomain.writeProjectModel, writes model.json");
    }
}
