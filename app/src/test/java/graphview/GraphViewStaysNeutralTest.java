package graphview;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Locks the reusable renderer above providers and below application adapters. */
class GraphViewStaysNeutralTest {
    private static final Path SOURCE = Path.of("src/main/java/graphview");
    private static final Pattern IMPORT = Pattern.compile(
            "^import\\s+(?:static\\s+)?([^;]+);", Pattern.MULTILINE);
    private static final Set<String> FOUNDATIONS =
            Set.of("java", "javax", "javafx", "netscape", "graphview", "objectview", "org");

    @Test void graphViewDoesNotKnowAnyDatasourceOrApplication() throws Exception {
        List<String> offenders = new ArrayList<>();
        try (var files = Files.walk(SOURCE)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                var matcher = IMPORT.matcher(Files.readString(file));
                while (matcher.find()) {
                    String imported = matcher.group(1).trim();
                    if (!FOUNDATIONS.contains(imported.split("\\.")[0])
                            && !imported.startsWith("com.fasterxml.jackson.")) {
                        offenders.add(file.getFileName() + " imports " + imported);
                    }
                }
            }
        }
        assertEquals(List.of(), offenders,
                "graphview renders provider-neutral nodes and edges; adapters live above it");
    }
}
