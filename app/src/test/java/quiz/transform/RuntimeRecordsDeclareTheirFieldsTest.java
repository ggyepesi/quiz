package quiz.transform;

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
            "quiz/transform/ui/Projector.java",
            "a projection declares its new type's fields to the derived domain",
            "quiz/transform/ui/Joiner.java",
            "a join declares its new type's two reference fields to the derived domain",
            "quiz/transform/ui/DomainReferenceResolver.java",
            "a resolved reference is a stub of a domain type and writes no field");

    private static final Pattern RECORD_VARIABLE =
            Pattern.compile("DynamicViewable\\s+(\\w+)\\s*=\\s*new\\s+DynamicViewable\\(");
    private static final Pattern DECLARED_FIELD = Pattern.compile(
            "RecordTypes\\.(?:text|number|value|reference|references|record|records)"
                    + "\\(\\s*\"([^\"]+)\"");

    @Test void everyFieldARuntimeRecordWritesIsDeclared() throws IOException {
        List<String> undeclared = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : (Iterable<Path>) files
                    .filter(f -> f.toString().endsWith(".java"))::iterator) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String relative = SOURCES.relativize(file).toString().replace('\\', '/');
                if (relative.equals("quiz/transform/DynamicViewable.java")) continue;

                Set<String> records = new LinkedHashSet<>();
                Matcher variable = RECORD_VARIABLE.matcher(source);
                while (variable.find()) records.add(variable.group(1));
                if (records.isEmpty() && !source.contains("new DynamicViewable(")) continue;

                boolean declares = source.contains("RecordTypes.declare(");
                if (!declares && !DOMAIN_BACKED.containsKey(relative)) {
                    undeclared.add(relative + ": builds records but declares no record type");
                    continue;
                }

                Set<String> declared = new LinkedHashSet<>();
                Matcher field = DECLARED_FIELD.matcher(source);
                while (field.find()) declared.add(field.group(1));
                for (String record : records) {
                    Matcher put = Pattern.compile(
                            "\\b" + Pattern.quote(record) + "\\.put\\(\\s*\"([^\"]+)\"")
                            .matcher(source);
                    while (put.find()) {
                        if (!declared.contains(put.group(1))) {
                            undeclared.add(relative + ": " + record + " writes \""
                                    + put.group(1) + "\", which its producer never declares");
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), undeclared,
                "declare the record type's fields in RecordTypes where it is produced");
    }
}
