package wikidata;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The same forcing rule as objectview's {@code NameBasedRoleGuardTest}, applied to this
 * module: no code infers a role from a field's literal NAME.
 *
 * <p>It exists because the objectview guard scans {@code objectview/src/main/java} and
 * nothing else, so the module holding most of the domain logic was unguarded — and the
 * rule's own history is in app, not objectview: the Oscar fields, then the provenance
 * {@code source} field whose {@code @Link} was called {@code qid} and so was read as a
 * Wikidata entity. A graph result most recently decided which node produced its output
 * class by asking whether a label began with "Graph node ", the placeholder written for a
 * node that has none.
 *
 * <p>The two guards are deliberately separate rather than shared: the modules are
 * separate Maven projects and test scope is not transitive, so a common helper would have
 * to become production code that exists only for tests. They keep the same pattern and
 * each owns its allowlist. A NEW occurrence fails the build; a removed one must be
 * deleted from the allowlist, so the list only ever shrinks to zero.
 */
class AppNameBasedRoleGuardTest {

    // Kept identical to objectview's NameBasedRoleGuardTest. Change both together.
    private static final Pattern ANTIPATTERN = Pattern.compile(
            "\"(?:qid|name|id|identifier|source|record)\"\\s*\\.equals(?:IgnoreCase)?"
            + "|\\.equals(?:IgnoreCase)?\\(\\s*\"(?:qid|name|id|identifier|source|record)\""
            + "|rawDeclaredField\\([^)]*\"(?:qid|name|id|identifier|source|record)\""
            + "|(?:containsKey|hasRootPath|new FieldPath)\\([^)]*"
            + "\"(?:qid|name|id|identifier|source|record)\""
            + "|case\\s+\"(?:qid|name|id|identifier|source|record)\"");

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    @Test
    void theAppNeverInfersARoleFromAFieldName() throws Exception {
        Set<String> current = scan();
        Set<String> allowed = loadAllowlist();

        Set<String> added = new TreeSet<>(current);
        added.removeAll(allowed);
        Set<String> resolved = new TreeSet<>(allowed);
        resolved.removeAll(current);

        StringBuilder msg = new StringBuilder();
        if (!added.isEmpty()) {
            msg.append("\nNEW name-based special-casing (forbidden — declare the role via an "
                    + "annotation/flag/contract, do NOT match a field name):\n");
            added.forEach(v -> msg.append("  + ").append(v).append('\n'));
        }
        if (!resolved.isEmpty()) {
            msg.append("\nAllowlisted occurrences that are gone (progress!) — delete these "
                    + "lines from app-name-based-role-allowlist.txt:\n");
            resolved.forEach(v -> msg.append("  - ").append(v).append('\n'));
        }
        assertTrue(added.isEmpty() && resolved.isEmpty(), msg.toString());
    }

    private static Set<String> scan() throws Exception {
        Set<String> hits = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path f : (Iterable<Path>) files
                    .filter(p -> p.toString().endsWith(".java"))::iterator) {
                String rel = SOURCE_ROOT.relativize(f).toString().replace('\\', '/');
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String code = line.split("//", 2)[0];
                    String trimmed = code.strip();
                    if (trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;
                    if (ANTIPATTERN.matcher(code).find()) {
                        hits.add(rel + "::" + trimmed);
                    }
                }
            }
        }
        return hits;
    }

    private static Set<String> loadAllowlist() throws Exception {
        Set<String> allow = new TreeSet<>();
        try (InputStream in = AppNameBasedRoleGuardTest.class
                .getResourceAsStream("/app-name-based-role-allowlist.txt")) {
            if (in == null) {
                fail("app-name-based-role-allowlist.txt is missing from test resources");
            }
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            List<String> lines = r.lines().map(String::strip)
                    .filter(s -> !s.isEmpty()).toList();
            allow.addAll(lines);
        }
        return allow;
    }
}
