package objectview;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Directive 24: "all fields" and "all minor fields" are rewritten into plain ticks
 * once, where a config enters, by {@code ViewConfigDesugar}. They used to be read by
 * the editor, Card, ValueRenderer, the table, the search and sort collectors, the web
 * serializer and quiz extraction, each with its own fallback, so one config rendered
 * differently in each. No main source outside the desugar (and the config's own
 * storage) may read them again.
 */
class ShorthandIsReadOnlyAtTheBoundaryTest {

    private static final List<Path> ROOTS = List.of(
            Path.of("src/main/java"), Path.of("../objectview/src/main/java"));

    /** The one reader, and the config's own definition and persistence. */
    private static final Set<String> ALLOWED = Set.of(
            "ViewConfigDesugar.java", "ViewConfig.java", "ViewConfigJsonIO.java");

    @Test void onlyTheDesugarReadsTheShorthandFlags() throws IOException {
        List<String> readers = new ArrayList<>();
        for (Path root : ROOTS) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : (Iterable<Path>) files.filter(f -> f.toString()
                        .endsWith(".java"))::iterator) {
                    if (ALLOWED.contains(file.getFileName().toString())) continue;
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        if (line.contains("isAllFields()") || line.contains("isAllMinorFields()")) {
                            readers.add(file + ":" + (i + 1) + ": " + line.trim());
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), readers,
                "rewrite the config with ViewConfigDesugar where it enters instead");
    }
}
