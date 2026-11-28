package domain.transform;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A record built at runtime ({@link DynamicViewable}) has exactly the fields its producer
 * declares in {@code RecordTypes} (#363). Its values never say what fields exist, so a
 * producer that writes a field it did not declare shows a card without it, and one that
 * declares nothing shows the caption alone — silently, with every other test green.
 *
 * <p>Two rules, read off every main source that builds such a record: each field it
 * writes under a literal name is declared under that name in the same file, and the
 * file declares its record types at all. A producer whose records are instances of a
 * domain type declares them to the domain instead and is named below with that reason.
 */
class RuntimeRecordsDeclareTheirFieldsTest {

    private static final Path SOURCES = Path.of("src/main/java");

    /** Producers whose record types are domain types, declared to the domain they build. */
    private static final Map<String, String> DOMAIN_BACKED = Map.of(
            "domain/transform/ui/Projector.java",
            "a projection declares its new type's fields to the derived domain",
            "domain/transform/ui/Joiner.java",
            "a join declares its new type's two reference fields to the derived domain");

    private static final String RECORD_TYPE = "(?:domain\\s*\\.\\s*transform\\s*\\.\\s*)?DynamicViewable";
    private static final String NEW_RECORD = "\\bnew\\s+" + RECORD_TYPE + "\\s*\\(";
    private static final Pattern RECORD_CONSTRUCTION = Pattern.compile(NEW_RECORD);
    private static final Pattern RECORD_VARIABLE = Pattern.compile(
            "\\b(?:var|" + RECORD_TYPE + ")\\s+(\\w+)\\s*=\\s*" + NEW_RECORD);
    private static final Pattern DECLARED_FIELD = Pattern.compile(
            "RecordTypes\\.(?:display|text|number|value|reference|references|record|records)"
                    + "\\(\\s*\"([^\"]+)\"");

    @Test void everyFieldARuntimeRecordWritesIsDeclared() throws IOException {
        List<String> undeclared = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : (Iterable<Path>) files
                    .filter(f -> f.toString().endsWith(".java"))::iterator) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String relative = SOURCES.relativize(file).toString().replace('\\', '/');
                if (relative.equals("domain/transform/DynamicViewable.java")) continue;

                undeclared.addAll(missingDeclarations(relative, source));
            }
        }
        assertEquals(List.of(), undeclared,
                "declare the record type's fields in RecordTypes where it is produced");
    }

    @Test void fullyQualifiedConstructionNeedsADeclarationJustLikeAnImport() {
        for (String type : List.of("DynamicViewable", "domain.transform.DynamicViewable")) {
            assertEquals(List.of("Producer.java: builds records but declares no record type"),
                    missingDeclarations("Producer.java", "return new " + type + "(\"Q1\", \"One\");"));
        }
    }

    @Test void qualifiedConstructorsTrackFieldWritesWithExplicitAndInferredTypes() {
        for (String type : List.of("var", "DynamicViewable", "domain.transform.DynamicViewable")) {
            String producer = """
                    RecordTypes.declare("Run", RecordTypes.text("Declared"));
                    %s record = new
                            domain.transform.DynamicViewable ("r1", "Run");
                    record.put("Declared", "present");
                    """.formatted(type);
            assertEquals(List.of(), missingDeclarations("Producer.java", producer));
            assertEquals(List.of("Producer.java: record writes \"Missing\", which its producer never declares"),
                    missingDeclarations("Producer.java", producer + "record.put(\"Missing\", 1);"));
        }
    }

    private static List<String> missingDeclarations(String relative, String source) {
        if (!RECORD_CONSTRUCTION.matcher(source).find()) return List.of();
        if (!source.contains("RecordTypes.declare(") && !DOMAIN_BACKED.containsKey(relative)) {
            return List.of(relative + ": builds records but declares no record type");
        }

        Set<String> records = new LinkedHashSet<>();
        Matcher variable = RECORD_VARIABLE.matcher(source);
        while (variable.find()) records.add(variable.group(1));

        Set<String> declared = new LinkedHashSet<>();
        Matcher field = DECLARED_FIELD.matcher(source);
        while (field.find()) declared.add(field.group(1));
        List<String> missing = new ArrayList<>();
        for (String record : records) {
            Matcher put = Pattern.compile(
                    "\\b" + Pattern.quote(record) + "\\.put\\(\\s*\"([^\"]+)\"")
                    .matcher(source);
            while (put.find()) {
                if (!declared.contains(put.group(1))) {
                    missing.add(relative + ": " + record + " writes \"" + put.group(1)
                            + "\", which its producer never declares");
                }
            }
        }
        return missing;
    }
}
