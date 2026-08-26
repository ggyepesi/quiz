package wikidata.explore.query.logical;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A query that issues SPARQL runs inside a log step.
 *
 * <p>The raw request reaches the endpoint log either way, so the symptom of
 * forgetting is not silence — it is a workflow step with nothing in it: no
 * parameters, no runnable link, no row count. A reader watching a slow discovery
 * then has a spinner and no way to see what it is waiting on, which is how
 * DiscoverClassPropertiesQuery hid two parallel requests behind one bare step.
 */
class LoggedSparqlGuardTest {

    private static final Path LOGICAL =
            Path.of("src/main/java/wikidata/explore/query/logical");

    @Test void everyQueryThatAsksSparqlOpensAStepForIt() throws Exception {
        List<String> unlogged = new ArrayList<>();
        try (var files = Files.list(LOGICAL)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                boolean asksSparql = source.contains("queryAsync(") || source.contains(".query(");
                boolean opensStep = source.contains("context.step(")
                        || source.contains(".step(");
                if (asksSparql && !opensStep) {
                    unlogged.add(file.getFileName().toString());
                }
            }
        }
        assertTrue(unlogged.isEmpty(),
                "these issue SPARQL outside a log step, so their workflow step shows "
                        + "no parameters, no runnable request and no result count: "
                        + unlogged);
    }
}
