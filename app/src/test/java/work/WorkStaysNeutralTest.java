package work;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A unit of work is not a Wikidata unit of work.
 *
 * <p>{@code Query}, its context and the workflow log used to live inside
 * {@code wikidata.explore.query}, and {@code QueryContext} held a Wikidata SPARQL client
 * map and a Wikidata API client as fields. Everything that wanted something observable and
 * cancellable therefore imported Wikidata to get it: the generic process runner, the
 * provider-neutral enrichment layer, the Wikipedia client — and {@code QueryContext}
 * imported {@code process.CancellationToken} back, so the generic runner and the Wikidata
 * query package depended on each other.
 *
 * <p>Nothing about that was visible at a call site; it only showed up in the import graph.
 * So the rule is enforced from the import graph: this package may depend on the JDK and on
 * nothing else in this repository. A source binding — Wikidata's, DBpedia's, anyone's —
 * arrives as a capability the runner binds ({@code QueryContext.with}), which is a
 * dependency pointing the other way.
 */
class WorkStaysNeutralTest {

    private static final Path SOURCE = Path.of("src/main/java/work");
    private static final Pattern IMPORT =
            Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+);", Pattern.MULTILINE);
    /** The JDK, and objectview — the rendering foundation the whole repo sits on, which
     *  knows nothing of any of it. {@code LogNode} is a Viewable because the workflow log
     *  is read in the same card views as everything else. Every OTHER root here would be
     *  a source or an application reaching into the contract meant to serve it. */
    private static final Set<String> FOUNDATION = Set.of("java", "javax", "objectview");

    @Test void theWorkContractDependsOnNothingInThisRepository() throws Exception {
        Set<String> offenders = new TreeSet<>();
        for (Path file : sources()) {
            String source = Files.readString(file);
            Matcher matcher = IMPORT.matcher(source);
            while (matcher.find()) {
                String imported = matcher.group(1);
                String root = imported.split("\\.")[0];
                if (FOUNDATION.contains(root) || "work".equals(root)) continue;
                offenders.add(file.getFileName() + " imports " + imported);
            }
        }

        assertEquals(Set.of(), offenders,
                "work is the contract every source binds into; it binds into none of them");
    }

    /** The capability seam is the whole point, so it has to actually be usable. */
    @Test void aContextCarriesWhateverAccessItsRunnerBoundAndNothingMore() {
        QueryContext plain = new QueryContext();
        assertTrue(plain.optional(String.class) == null, "nothing is bound by default");

        QueryContext bound = plain.with(String.class, "an endpoint");

        assertEquals("an endpoint", bound.require(String.class));
        assertTrue(plain.optional(String.class) == null, "binding does not mutate the original");
    }

    @Test void askingForAccessNobodyBoundNamesWhatWasMissing() {
        IllegalStateException refused = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> new QueryContext().require(Integer.class));

        assertTrue(refused.getMessage().contains("Integer"), refused.getMessage());
    }

    private static List<Path> sources() throws Exception {
        assertTrue(Files.isDirectory(SOURCE), "run from the app module: " + SOURCE.toAbsolutePath());
        try (Stream<Path> files = Files.walk(SOURCE)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }
}
