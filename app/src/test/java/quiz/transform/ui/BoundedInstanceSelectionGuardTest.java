package quiz.transform.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A source-discovery workbench selects its instances through
 * {@link BoundedInstanceSelection}. Wikipedia Discovery's selection controls were copied
 * whole into Commons Discovery, and the two copies already disagreed on a selected
 * instance's identity; a third copy is the next workbench away.
 */
class BoundedInstanceSelectionGuardTest {

    private static final Path SOURCES = Path.of("src/main/java");
    /** Known copies not yet moved onto the shared control. This list only shrinks. */
    private static final Map<String, Integer> NOT_YET_SHARED = Map.of(
            // TransformApp's experimental source inspection: its own instance/selected tabs.
            "quiz/transform/ui/TransformWorkbenchPanel.java", 1);

    @Test void onlyTheSharedSelectionBuildsTheSampleAndSelectionControls()
            throws IOException {
        Map<String, Integer> remainingCopies = new java.util.TreeMap<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                String relative = SOURCES.relativize(file).toString();
                if (file.getFileName().toString().equals("BoundedInstanceSelection.java")) {
                    continue;
                }
                String source = Files.readString(file);
                int copies = occurrences(source, "new JButton(\"Start random sample\")")
                        + occurrences(source, "new JButton(\"Add selection\")");
                if (copies > 0) remainingCopies.put(relative, copies);
            }
        }

        assertEquals(NOT_YET_SHARED, remainingCopies,
                "select instances through BoundedInstanceSelection; when a known copy "
                        + "is removed, lower its measured baseline instead of silently "
                        + "leaving the whole file exempt");
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(part, at)) >= 0; at += part.length()) count++;
        return count;
    }
}
