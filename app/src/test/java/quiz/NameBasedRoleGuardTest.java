package quiz;

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
 * The app-module half of the FORCING RULE (see objectview's {@code NameBasedRoleGuardTest}):
 * no code may infer a field/class ROLE from its literal NAME. This scans the whole app source
 * for the anti-pattern and ratchets a frozen allowlist ({@code name-based-role-allowlist.txt}):
 * a NEW occurrence fails the build; each one migrated onto a declared role/annotation must be
 * deleted from the allowlist, so the list only shrinks to zero.
 *
 * <p>Deliberately precise — it matches a role-name literal only in a COMPARISON context
 * ({@code .equals}/{@code .equalsIgnoreCase}/{@code case}/reflective lookup), so ordinary
 * uses of {@code "qid"} as a query parameter or map key are not flagged.</p>
 */
class NameBasedRoleGuardTest {

    private static final Pattern ANTIPATTERN = Pattern.compile(
            "\"(?:qid|name|id|identifier|source|record)\"\\s*\\.equals(?:IgnoreCase)?"
            + "|\\.equals(?:IgnoreCase)?\\(\\s*\"(?:qid|name|id|identifier|source|record)\""
            + "|rawDeclaredField\\([^)]*\"(?:qid|name|id|identifier|source|record)\""
            + "|(?:containsKey|hasRootPath|new FieldPath)\\([^)]*"
            + "\"(?:qid|name|id|identifier|source|record)\""
            + "|case\\s+\"(?:qid|name|id|identifier|source|record)\"");

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    @Test
    void appNeverInfersARoleFromAFieldName() throws Exception {
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
                    + "lines from name-based-role-allowlist.txt:\n");
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
        try (InputStream in = NameBasedRoleGuardTest.class
                .getResourceAsStream("/name-based-role-allowlist.txt")) {
            if (in == null) fail("name-based-role-allowlist.txt is missing from test resources");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            allow.addAll(r.lines().map(String::strip).filter(s -> !s.isEmpty()).toList());
        }
        return allow;
    }
}
