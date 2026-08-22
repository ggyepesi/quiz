package objectview.field;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #87: a domain object's fields live in one of two places — declared Java fields on a
 * hand-written Viewable, or the property map of a snapshot object — and every consumer
 * that wanted a field asked which. Each fork is a place the two backings can drift, and
 * they did: a dynamic value rendered where a declared one did not, a path resolved on
 * one and returned null on the other.
 *
 * <p>{@link FieldSet#of} is the single place that decision is made. This test fails on
 * the next consumer that makes it again.
 */
class OneFieldAccessPathTest {

    private static final Pattern FORK =
            Pattern.compile("instanceof\\s+(objectview\\.field\\.)?DynamicFields");

    /**
     * {@link FieldSet} itself is the factory that picks the backing — the one place the
     * question belongs. {@link DynamicFieldSet} and {@link LayeredFieldSet} are the
     * backing it picks. Anything else asking is a consumer that should have used the
     * seam.
     */
    private static final List<String> THE_SEAM_ITSELF = List.of(
            "objectview/field/FieldSet.java",
            "objectview/field/DynamicFieldSet.java",
            "objectview/field/LayeredFieldSet.java");

    @Test void noConsumerBranchesOnWhereAFieldHappensToBeStored() throws IOException {
        List<String> forks = new ArrayList<>();

        for (Path file : mainJavaFiles()) {
            String relative = file.toString().replace('\\', '/');
            if (THE_SEAM_ITSELF.stream().anyMatch(relative::endsWith)) {
                continue;
            }
            String source = stripComments(Files.readString(file));
            if (FORK.matcher(source).find()) {
                forks.add(relative);
            }
        }

        assertTrue(forks.isEmpty(),
                "Read through FieldSet.of(object) instead — it picks the backing, so a "
                        + "declared field and a snapshot field answer alike:\n  "
                        + String.join("\n  ", forks));
    }

    @Test void theSeamReadsBothBackingsThroughOneCall() {
        assertTrue(FieldSet.of(new Declared()).has("title"));
        assertTrue(FieldSet.of(new Dynamic()).has("title"));
        assertTrue("t".equals(FieldSet.of(new Declared()).read("title")));
        assertTrue("t".equals(FieldSet.of(new Dynamic()).read("title")));
    }

    @Test void theSeamWritesToBothBackingsThroughOneCall() {
        Declared declared = new Declared();
        Dynamic dynamic = new Dynamic();

        FieldSet.of(declared).write("title", "changed");
        FieldSet.of(dynamic).write("title", "changed");

        assertTrue("changed".equals(FieldSet.of(declared).read("title")));
        assertTrue("changed".equals(FieldSet.of(dynamic).read("title")));
    }

    private static List<Path> mainJavaFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path root : List.of(Path.of("src", "main", "java"),
                Path.of("..", "objectview", "src", "main", "java"))) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            }
        }
        assertTrue(files.size() > 100, "both source trees must be scanned");
        return files;
    }

    /** A javadoc saying "no instanceof DynamicFields fork" is documentation, not one. */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "")
                     .replaceAll("(?m)^\\s*//.*$", "");
    }

    private static final class Declared extends objectview.ViewableAdapter {
        private String title = "t";
        @Override public String getIdentifier() { return title; }
        @Override public String getDisplayName() { return title; }
    }

    private static final class Dynamic extends objectview.ViewableAdapter
            implements DynamicFields {
        private final java.util.Map<String, Object> values =
                new java.util.LinkedHashMap<>(java.util.Map.of("title", "t"));
        @Override public java.util.Map<String, Object> dynamicFieldValues() { return values; }
        @Override public String getIdentifier() { return "d"; }
        @Override public String getDisplayName() { return "d"; }
    }
}
