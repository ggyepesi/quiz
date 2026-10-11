package architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** The numbered architecture rules are stable addresses used by docs and code comments. */
class ArchitectureDirectiveNumberingTest {
    private static final Pattern DIRECTIVE =
            Pattern.compile("(?m)^(\\d+)\\. \\*\\*");

    @Test void architectureDirectiveNumbersAreUniqueAndConsecutive() throws Exception {
        Path guidance = findGuidance();
        assertNotNull(guidance, "CLAUDE.md must be reachable from the test checkout");
        var matcher = DIRECTIVE.matcher(Files.readString(guidance));
        List<Integer> actual = new ArrayList<>();
        while (matcher.find()) actual.add(Integer.parseInt(matcher.group(1)));

        List<Integer> expected = java.util.stream.IntStream
                .rangeClosed(1, actual.getLast()).boxed().toList();
        assertEquals(expected, actual,
                "a duplicate or missing number makes directive references ambiguous");
    }

    private static Path findGuidance() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("CLAUDE.md");
            if (Files.isRegularFile(candidate)) return candidate;
            directory = directory.getParent();
        }
        return null;
    }
}
