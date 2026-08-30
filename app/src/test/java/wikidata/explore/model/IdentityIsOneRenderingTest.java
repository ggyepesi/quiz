package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Three functions answered "what text identifies this value", and they disagreed.
 *
 * <p>A canonical key took the FIRST element of a collection, so three physicists who
 * shared one Nobel prize for one achievement were identified by whichever was listed
 * first and could never merge. An aggregate key sorted the whole collection. A reify
 * dedup key sorted it too, but like the other two fell through to {@code toString()}
 * for a value type that publishes a canonical form — the one thing that form exists to
 * prevent. Fixing the third would have been a fix that lasts until the fourth.
 *
 * <p>{@link StableIdentity} is that answer for MODEL identity, and it is not the only
 * regime: evidence identity is stricter for reasons of its own, and the two owners are
 * named below with the difference between them. This fails the moment a THIRD file
 * reduces a possibly-collection value to identifying text on its own.
 */
class IdentityIsOneRenderingTest {


    /** A collection joined into one string, in a file that also reads an identity. */
    private static final Pattern JOINS_A_COLLECTION =
            Pattern.compile("instanceof Collection.{0,400}?joining\\(", Pattern.DOTALL);

    /**
     * The two identity regimes, which differ where their domains differ. Model identity
     * treats every collection as a SET, because co-laureates listed in another order are
     * the same participants, and tolerates an unknown type. Evidence identity preserves
     * LIST order, type-qualifies every value so a number and its text never collide, and
     * REFUSES a type it does not understand rather than hashing a rendering of it. One
     * may not be expressed in terms of the other; a third is what this test exists for.
     */
    private static final List<String> OWNERS = List.of(
            "datasource/evidence/ExtractedClaim.java",
            "wikidata/explore/model/StableIdentity.java");

    /**
     * Formats a value for a reader, not for a key: it truncates at five values and says
     * "…". The scan reads whole files, so a file that renders for display and mentions
     * identity elsewhere lands here; this list may shrink and must not grow.
     */
    private static final List<String> DISPLAY_ONLY = List.of(
            "quiz/curation/ui/MergePanel.java");

    /** Every place that historically rendered identity, and must now ask instead. */
    private static final List<String> DELEGATES = List.of(
            "wikidata/explore/model/Canonicalizer.java",
            "wikidata/explore/transform/ModelAggregates.java",
            "wikidata/explore/transform/TransformEngine.java");

    @Test void onlyOneFileRendersAValueAsIdentity() throws IOException {
        List<String> renderers = new ArrayList<>();
        for (Path file : mainJavaFiles()) {
            String source = withoutComments(Files.readString(file));
            boolean readsIdentity = source.contains("getIdentifier()")
                    || source.contains("stableForm()");
            if (readsIdentity && JOINS_A_COLLECTION.matcher(source).find()) {
                renderers.add(relative(file));
            }
        }

        java.util.Collections.sort(renderers);
        List<String> expected = new ArrayList<>(OWNERS);
        expected.addAll(DISPLAY_ONLY);
        java.util.Collections.sort(expected);

        assertEquals(expected, renderers,
                "identity text comes from an owner above, and from nowhere else");
    }

    @Test void thePlacesThatUsedToRenderItNowAskForIt() throws IOException {
        for (String delegate : DELEGATES) {
            String source = Files.readString(Path.of("src/main/java", delegate));
            assertTrue(source.contains("StableIdentity"),
                    delegate + " must ask StableIdentity rather than answer for itself");
        }
    }

    private static List<Path> mainJavaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private static String relative(Path file) {
        return Path.of("src", "main", "java").relativize(file).toString().replace('\\', '/');
    }

    /** Prose about identity is not an implementation of it. */
    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", "");
    }
}
