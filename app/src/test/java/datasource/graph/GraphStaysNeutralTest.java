package datasource.graph;

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

/** Locks the provider/UI-neutral boundary before adapters begin to multiply. */
class GraphStaysNeutralTest {
    private static final Path SOURCE = Path.of("src/main/java/datasource/graph");
    private static final Pattern IMPORT = Pattern.compile(
            "^import\\s+(?:static\\s+)?([^;]+);", Pattern.MULTILINE);
    private static final Pattern STRING = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
    private static final Pattern NATIVE_IDENTIFIER = Pattern.compile(
            "(?:^|[^A-Za-z0-9])(?:Q[1-9][0-9]*|P[1-9][0-9]*)(?:$|[^A-Za-z0-9])");
    // A bare provider id is the violation that matters most: an adapter passes
    // "wikidata" into GraphRelation, and the day that literal appears in here the
    // core has stopped being neutral. Prefix forms still require their colon, so
    // ordinary words are not mistaken for them.
    private static final Pattern PROVIDER_LITERAL = Pattern.compile(
            "(?i)(?:https?://"
            + "|(?:^|[^a-z])(?:wikidata|wikipedia|dbpedia|wikisource|commons)"
            + "|(?:^|[^a-z])wdt?:)");
    private static final Set<String> ALLOWED_ROOTS = Set.of("java", "datasource", "batch");

    @Test void graphCoreImportsOnlyNeutralFoundations() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path file : sources()) {
            String source = Files.readString(file);
            Matcher imports = IMPORT.matcher(source);
            while (imports.find()) {
                String imported = imports.group(1).trim();
                String root = imported.split("\\.")[0];
                if (!ALLOWED_ROOTS.contains(root)) {
                    offenders.add(file.getFileName() + " imports " + imported);
                }
            }
        }
        assertEquals(List.of(), offenders,
                "datasource.graph is the neutral graph contract; providers and UI bind into it");
    }

    @Test void graphCoreContainsNoProviderIdentifiersHiddenInStrings() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path file : sources()) {
            String source = code(Files.readString(file));
            Matcher strings = STRING.matcher(source);
            while (strings.find()) {
                String literal = strings.group();
                if (NATIVE_IDENTIFIER.matcher(literal).find()
                        || PROVIDER_LITERAL.matcher(literal).find()) {
                    int line = 1 + (int) source.substring(0, strings.start()).chars()
                            .filter(ch -> ch == '\n').count();
                    offenders.add(file.getFileName() + ":" + line + " contains " + literal);
                }
            }
        }
        assertEquals(List.of(), offenders,
                "provider identifiers and URLs belong in adapters, not datasource.graph");
    }

    /**
     * Comments are documentation, not contract: a javadoc that quotes "wikidata" as an
     * example provider id explains the boundary rather than crossing it. Line numbers
     * are preserved so an offender still reports where it is.
     */
    private static String code(String source) {
        return source.lines()
                .map(line -> {
                    String trimmed = line.strip();
                    if (trimmed.startsWith("*") || trimmed.startsWith("/*")
                            || trimmed.startsWith("*/")) return "";
                    return line.split("//", 2)[0];
                })
                .collect(java.util.stream.Collectors.joining("\n"));
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
