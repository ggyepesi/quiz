package workbench;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What one panel hands another says what it is carrying.
 *
 * <p>Six hand-offs once shared {@code BiConsumer<String, String>} across four meanings —
 * {@code (pid, label)}, {@code (pid, qid)}, {@code (qid, label)} and
 * {@code (className, fieldName)} — so wiring one to the wrong receiver compiled and did
 * the wrong thing in silence. {@link WorkbenchSelections} and typed request records fixed
 * the ones that mattered; this stops the shape coming back while the rest migrate.
 *
 * <p>The allowlist ratchets: a new occurrence fails, and one that is migrated must be
 * deleted from the list, so it only shrinks.
 */
class TypedHandoffsTest {

    // Qualified or imported, spaced or not: a hole in the pattern is a hole in the
    // rule, and the first probe of this guard slipped through it fully qualified.
    private static final Pattern UNTYPED_HANDOFF = Pattern.compile(
            "public void (on[A-Za-z]+)\\(\\s*(?:java\\.util\\.function\\.)?"
            + "BiConsumer<\\s*String\\s*,\\s*String\\s*>");

    @Test void aHandoffSaysWhatItCarries() throws IOException {
        List<String> current = new ArrayList<>();
        for (Path file : mainSources()) {
            String source = Files.readString(file);
            Matcher matcher = UNTYPED_HANDOFF.matcher(source);
            while (matcher.find()) {
                current.add(Path.of("src", "main", "java").relativize(file)
                        .toString().replace('\\', '/') + "::" + matcher.group(1));
            }
        }
        List<String> allowed = allowlist();
        List<String> added = new ArrayList<>(current);
        added.removeAll(allowed);
        List<String> migrated = new ArrayList<>(allowed);
        migrated.removeAll(current);

        assertEquals(List.of(), added,
                "a String pair cannot say whether it is (pid, label) or (pid, qid) — "
                        + "carry a WorkbenchSelections value or a named request record");
        assertEquals(List.of(), migrated,
                "these are typed now (progress!) — delete them from "
                        + "untyped-handoff-allowlist.txt");
    }

    private static List<String> allowlist() throws IOException {
        try (InputStream in = TypedHandoffsTest.class
                .getResourceAsStream("/untyped-handoff-allowlist.txt")) {
            if (in == null) return List.of();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip).filter(line -> !line.isEmpty()).toList();
        }
    }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }
}
