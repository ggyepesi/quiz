package wikidata.query;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #90: ~52 Oscar referents came back as bare QIDs because the {@code en,mul} fallback
 * had been added to ONE query builder while two others still asked for {@code "en"}
 * alone. Any entity whose only label is the {@code mul} multilingual default — star
 * Albaldah (Q14044), a nominee or work with no English label — resolved to its QID.
 *
 * <p>Fixing the third spot would have been a fix that lasts until the fourth. The
 * language a Wikidata label query asks for is ONE decision, and {@link LabelService} is
 * where it is made; this test fails the moment a query builder makes it again.
 */
class LabelLanguageIsOneDecisionTest {

    /** The Wikidata label SERVICE's language parameter. */
    private static final Pattern SERVICE_LANGUAGE =
            Pattern.compile("wikibase:language\\s+\"");

    /** The other form: an explicit {@code ?x rdfs:label ?xLabel} triple filtered by
     *  language. {@code = "en"} excludes a mul-only label exactly as the SERVICE did. */
    private static final Pattern LABEL_LANGUAGE_EQUALS =
            Pattern.compile("LANG\\(\\??[A-Za-z_][\\w]*\\)\\s*=\\s*\"");

    /**
     * DBpedia is a different endpoint with different data: it has no label SERVICE and
     * no {@code mul} language, so {@code FILTER(LANG(?l) = "en")} is the correct and
     * only question to ask there. Excluding these is a boundary of what LabelService
     * governs (Wikidata labels), not an exception to it.
     */
    private static final List<String> DBPEDIA_QUERIES = List.of(
            "quiz/transform/ui/DBpediaLookup.java",
            "wikidata/explore/extract/DBpediaEnrichment.java");

    @Test void noQueryBuilderDecidesTheWikidataLabelLanguageForItself() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path file : mainJavaFiles()) {
            String relative = relative(file);
            if (relative.endsWith("wikidata/query/LabelService.java")
                    || DBPEDIA_QUERIES.stream().anyMatch(relative::endsWith)) {
                continue;
            }
            String source = stripComments(Files.readString(file));
            if (SERVICE_LANGUAGE.matcher(source).find()) {
                offenders.add(relative + " spells the label SERVICE language itself");
            }
            if (LABEL_LANGUAGE_EQUALS.matcher(source).find()) {
                offenders.add(relative + " filters labels by a single language");
            }
        }

        assertTrue(offenders.isEmpty(),
                "Route these through LabelService.service(...) / "
                        + "LabelService.labelFilter(...) so the en,mul fallback stays one "
                        + "decision:\n  " + String.join("\n  ", offenders));
    }

    @Test void theFallbackIsTheWholePointAndIsIdempotent() {
        assertEquals("en,mul", LabelService.language(null));
        assertEquals("en,mul", LabelService.language("en"));
        assertEquals("hu,mul", LabelService.language("hu"));
        assertEquals("en,mul", LabelService.language("en,mul"),
                "applying it twice must not append mul again");
    }

    @Test void bothFormsAskForTheSameLanguages() {
        assertTrue(LabelService.service("en").contains("\"en,mul\""));
        assertEquals("FILTER(LANG(?valueLabel) IN (\"en\", \"mul\"))",
                LabelService.labelFilter("valueLabel", "en"));
        assertEquals("FILTER(LANG(?valueLabel) IN (\"en\", \"mul\"))",
                LabelService.labelFilter("?valueLabel", null),
                "either spelling of the variable, so no second helper appears");
    }

    @Test void anyLanguageMeansNoFilterAtAll() {
        assertEquals("", LabelService.labelFilter("valueLabel", "any"));
    }

    /**
     * Guarding three remembered files is a guard that lasts until the fourth — the
     * same shape as the bug this class exists for. The whole tree is scanned, and the
     * remaining restatements are frozen in a ratcheted allowlist: a new one fails, and
     * one that is migrated onto {@link wikidata.WikidataLanguageDefaults} must be
     * deleted from the list, so it only shrinks.
     */
    @Test void noSourceRestatesTheDefaultLanguage() throws IOException {
        List<String> current = new ArrayList<>();
        for (Path file : mainJavaFiles()) {
            String relative = Path.of("src", "main", "java").relativize(file)
                    .toString().replace('\\', '/');
            if (relative.endsWith("WikidataLanguageDefaults.java")
                    || DBPEDIA_QUERIES.contains(relative)) continue;
            String source = stripComments(Files.readString(file));
            if (source.contains("\"en\"") || source.contains("\"en,mul\"")
                    || source.contains("\"en|mul\"") || source.contains("\"enwiki\"")) {
                current.add(relative);
            }
        }
        List<String> allowed = allowlist();
        List<String> added = new ArrayList<>(current);
        added.removeAll(allowed);
        List<String> resolved = new ArrayList<>(allowed);
        resolved.removeAll(current);

        assertTrue(added.isEmpty(),
                "NEW restatement of the default language — ask "
                        + "WikidataLanguageDefaults instead: " + added);
        assertTrue(resolved.isEmpty(),
                "these no longer restate it (progress!) — delete them from "
                        + "default-language-allowlist.txt: " + resolved);
    }

    private static List<String> allowlist() throws IOException {
        try (var in = LabelLanguageIsOneDecisionTest.class
                .getResourceAsStream("/default-language-allowlist.txt")) {
            if (in == null) return List.of();
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        }
    }

    private static List<Path> mainJavaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String relative(Path file) {
        return file.toString().replace('\\', '/');
    }

    /** A javadoc example of the pattern being replaced is documentation, not a query. */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "")
                     .replaceAll("(?m)^\\s*//.*$", "");
    }
}
