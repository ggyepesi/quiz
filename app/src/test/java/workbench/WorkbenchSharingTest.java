package workbench;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Neither workbench reaches into the other one's user interface.
 *
 * <p>They ask the same questions about where a field's data comes from, and there were only
 * two ways to share an answer: duplicate it, or import across. Both had happened — the
 * choose-a-source dialog was written out twice and survived being pointed at three times,
 * while the transform side imported seven types from ModelBuilder's UI package. Sharing now
 * means depending on {@code workbench}, which depends on neither app.
 *
 * <p>This is checked from the import graph because that is where it shows: two copies of a
 * dialog compile perfectly, and so does an import pointing the wrong way.
 */
class WorkbenchSharingTest {

    private static final String MODEL_BUILDER_UI = "wikidata.explore.workbench";
    private static final List<String> TRANSFORM_UI =
            List.of("quiz.transform.ui", "quiz.curation.ui");

    @Test void neitherAppsUserInterfaceImportsTheOthers() throws Exception {
        Set<String> offenders = new TreeSet<>();
        for (String app : TRANSFORM_UI) {
            for (Path file : sources(app)) {
                for (String imported : imports(file)) {
                    if (imported.startsWith(MODEL_BUILDER_UI + ".")) {
                        offenders.add(file.getFileName() + " → " + imported);
                    }
                }
            }
        }
        for (Path file : sources(MODEL_BUILDER_UI)) {
            for (String imported : imports(file)) {
                if (TRANSFORM_UI.stream().anyMatch(ui -> imported.startsWith(ui + "."))) {
                    offenders.add(file.getFileName() + " → " + imported);
                }
            }
        }

        assertEquals(Set.of(), offenders,
                "share it through the workbench package, which belongs to both");
    }

    /** And the shared package stays shareable: it may not depend on either app. */
    @Test void theSharedControlsDependOnNeitherApp() throws Exception {
        Set<String> offenders = new TreeSet<>();
        for (Path file : sources("workbench")) {
            for (String imported : imports(file)) {
                boolean anApp = imported.startsWith(MODEL_BUILDER_UI + ".")
                        || TRANSFORM_UI.stream().anyMatch(ui -> imported.startsWith(ui + "."));
                if (anApp) offenders.add(file.getFileName() + " → " + imported);
            }
        }

        assertEquals(Set.of(), offenders,
                "a shared control that knows one of its hosts is that host's control");
    }

    private static Set<String> imports(Path file) throws IOException {
        Set<String> found = new TreeSet<>();
        String source = Files.readString(file)
                .replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?<![\\w.])((?:[a-z][\\w]*\\.){2,}[A-Z][\\w]*)").matcher(source);
        while (matcher.find()) found.add(matcher.group(1));
        return found;
    }

    private static List<Path> sources(String packageName) throws IOException {
        Path root = Path.of("src/main/java", packageName.replace('.', '/'));
        assertTrue(Files.isDirectory(root), "no such package: " + root.toAbsolutePath());
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
    }
}
