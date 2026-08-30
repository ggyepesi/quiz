package datasource.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the provider-neutral source contract before another provider binds to it. */
class DatasourceApiStaysNeutralTest {
    private static final Path SOURCE = Path.of("src/main/java/datasource/api");
    private static final Pattern IMPORT = Pattern.compile(
            "^import\\s+(?:static\\s+)?([^;]+);", Pattern.MULTILINE);
    private static final Pattern STRING = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
    private static final Pattern NATIVE_IDENTIFIER = Pattern.compile(
            "(?:^|[^A-Za-z0-9])(?:Q[1-9][0-9]*|P[1-9][0-9]*)(?:$|[^A-Za-z0-9])");
    private static final Pattern PROVIDER_LITERAL = Pattern.compile(
            "(?i)(?:https?://|(?:^|[^a-z])"
                    + "(?:wikidata|wikipedia|dbpedia|wikisource|commons)"
                    + "|(?:^|[^a-z])wdt?:)");
    private static final Set<String> ALLOWED_ROOTS = Set.of("java", "datasource", "work");

    @Test void apiImportsOnlyNeutralFoundations() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path file : sources()) {
            Matcher imports = IMPORT.matcher(Files.readString(file));
            while (imports.find()) {
                String imported = imports.group(1).trim();
                if (!ALLOWED_ROOTS.contains(imported.split("\\.")[0])) {
                    offenders.add(file.getFileName() + " imports " + imported);
                }
            }
        }
        assertEquals(List.of(), offenders,
                "datasource.api is implemented by providers; it imports no provider or UI");
    }

    @Test void apiContainsNoProviderIdentifiersHiddenInStrings() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path file : sources()) {
            String source = code(Files.readString(file));
            Matcher strings = STRING.matcher(source);
            while (strings.find()) {
                String literal = strings.group();
                // Persisted before SourceBindingSlot became provider-neutral. The enum
                // owns this one migration spelling; no second provider literal is
                // allowed into the API contract.
                if (file.getFileName().toString().equals("SourceBindingSlot.java")
                        && literal.equals("\"wikipedia-category\"")) continue;
                if (NATIVE_IDENTIFIER.matcher(literal).find()
                        || PROVIDER_LITERAL.matcher(literal).find()) {
                    int line = 1 + (int) source.substring(0, strings.start()).chars()
                            .filter(ch -> ch == '\n').count();
                    offenders.add(file.getFileName() + ":" + line + " contains " + literal);
                }
            }
        }
        assertEquals(List.of(), offenders,
                "provider ids, native identifiers and URLs belong in provider adapters");
    }

    private static String code(String source) {
        return source.lines().map(line -> {
            String trimmed = line.strip();
            if (trimmed.startsWith("*") || trimmed.startsWith("/*")
                    || trimmed.startsWith("*/")) return "";
            return line.split("//", 2)[0];
        }).collect(java.util.stream.Collectors.joining("\n"));
    }

    private static List<Path> sources() throws Exception {
        assertTrue(Files.isDirectory(SOURCE),
                "run from the app module: " + SOURCE.toAbsolutePath());
        try (Stream<Path> files = Files.walk(SOURCE)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .sorted().toList();
        }
    }
}
