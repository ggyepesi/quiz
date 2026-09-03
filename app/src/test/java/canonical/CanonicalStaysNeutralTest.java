package canonical;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Identity and reduction are model semantics, and every datasource shares them.
 *
 * <p>That is only true while this package can say what it means without naming one. The
 * vocabulary lived inside Wikidata's transform layer, where a key was a list of field
 * names, "surrogate identity" was the absence of one, and which record survived a
 * collision was decided by an Oscars-shaped preference for the work-anchored copy. None
 * of that is wrong for Wikidata; all of it was wrong as the general rule.
 *
 * <p>The direction matters too: a datasource may APPLY the key this package describes,
 * and must not own or reimplement it. An import from here into a provider is expected;
 * an import from a provider into here is the boundary failing.
 */
class CanonicalStaysNeutralTest {

    private static final Pattern IMPORT =
            Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+);", Pattern.MULTILINE);
    private static final Set<String> FOUNDATION = Set.of("java", "javax", "objectview");

    @Test void theVocabularyNamesNoDatasource() throws Exception {
        Set<String> offenders = new TreeSet<>();
        for (Path file : sources()) {
            String source = Files.readString(file);
            Matcher matcher = IMPORT.matcher(source);
            while (matcher.find()) {
                String imported = matcher.group(1);
                String root = imported.split("\\.")[0];
                if (FOUNDATION.contains(root) || "canonical".equals(root)) continue;
                offenders.add(file.getFileName() + " imports " + imported);
            }
        }
        assertEquals(Set.of(), offenders,
                "identity and reduction are shared by every provider, so this package "
                        + "cannot depend on one");
    }

    /**
     * An import is invisible when the name is written out in full, and that is how a
     * dependency usually creeps back — a fully-qualified type inside a method body.
     */
    @Test void norDoesItNameOneWithoutImportingIt() throws Exception {
        Set<String> offenders = new TreeSet<>();
        for (Path file : sources()) {
            String source = Files.readString(file);
            for (String named : List.of("wikidata.", "dbpedia.", "wikipedia.")) {
                if (source.contains(named)) {
                    offenders.add(file.getFileName() + " names " + named);
                }
            }
        }
        assertEquals(Set.of(), offenders, offenders.toString());
    }

    /**
     * The rule that lets a reducer default while a key may not: a default can only be
     * one that cannot silently discard. If a new reduction is added, this fails until
     * somebody says which side of that line it falls on.
     */
    @Test void onlyTheReductionsThatCannotDiscardAreNonDestructive() {
        assertTrue(Reduction.REQUIRE_AGREEMENT.nonDestructive(), "it reports instead");
        assertTrue(Reduction.UNION_DISTINCT.nonDestructive(), "it keeps everything");
        assertFalse(Reduction.PREFER_NON_EMPTY.nonDestructive(), "it drops an empty");
        assertFalse(Reduction.CHOOSE_BY_POLICY.nonDestructive(), "it drops the losers");

        assertTrue(Reduction.defaultFor(true).nonDestructive());
        assertTrue(Reduction.defaultFor(false).nonDestructive());
    }

    private static List<Path> sources() throws Exception {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/canonical"))) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
