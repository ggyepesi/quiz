package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A painted diagram takes its colours from {@link DiagramStyle}.
 *
 * <p>The rule was stated once, in the first diagram, and the second re-implemented it:
 * text drawn in {@code Color.DARK_GRAY}, opaque near-white fills, and a white surface —
 * unreadable on a dark look and feel, which is exactly what stating the rule in one
 * class had already fixed. A convention that has to be remembered is one a new diagram
 * gets wrong, so this fails the moment a diagram decides its own colours.
 */
class DiagramStyleIsOneDecisionTest {

    @Test void noDiagramChoosesItsOwnColours() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : diagrams()) {
            if (file.getFileName().toString().equals("DiagramStyle.java")) continue;
            int line = 0;
            for (String text : Files.readAllLines(file)) {
                line++;
                String code = text.split("//", 2)[0].strip();
                if (code.startsWith("*") || code.startsWith("/*")) continue;
                if (code.contains("new Color(") || code.contains("UIManager")
                        || code.matches(".*\\bColor\\.[A-Z_]+.*")) {
                    offenders.add(file.getFileName() + ":" + line + "  " + code);
                }
            }
        }
        assertEquals(List.of(), offenders,
                "ask DiagramStyle instead — text follows the look and feel, the accent "
                        + "is shared, and a fill tints rather than replaces");
    }

    private static List<Path> diagrams() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            return files.filter(p -> p.toString().endsWith("Diagram.java")).sorted().toList();
        }
    }
}
