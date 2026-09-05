package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run refuses a model it cannot compile before it goes to the network.
 *
 * <p>Enrich used to acquire first and be told afterwards, which wasted the fetching.
 * This path never checked at all: it extracted, acquired, built a runtime and
 * materialized instances from a model nothing had refused, so the run looked like it
 * had worked.
 */
class FullRunValidationTest {

    @Test void anInvalidModelFailsBeforeTheFirstQuery() {
        GeneratedProjectModel invalid = new GeneratedProjectModel();
        invalid.name("movies");
        GeneratedClassModel movie = new GeneratedClassModel("Movie");
        movie.instanceMapping().propertyPid("P31");
        movie.instanceMapping().sourceQid("Q11424");
        invalid.rootClass(movie);
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new StatementClassSource("DoesNotExist", "P1411"));
        // A statement class states its key; nothing chooses one for it. This is what
        // the editor offers — the triple's own components — accepted explicitly.
        nomination.canonical().keyFields().addAll(
                wikidata.explore.model.StatementIdentity.structuralKey(nomination));
        invalid.addClass(nomination);

        RecordingClient client = new RecordingClient();

        IllegalStateException blocked = assertThrows(IllegalStateException.class,
                () -> new GenerationPipeline().fullRun(
                        invalid, 1, client, GenerationLog.NOOP, null,
                        new work.CancellationToken(), null, null));
        assertTrue(blocked.getMessage().contains("BLOCKED"), blocked.getMessage());

        assertTrue(client.queries.isEmpty(),
                "compilation must precede extraction: " + client.queries);
    }

    private static final class RecordingClient extends WikidataSparqlClient {
        private final List<String> queries = new ArrayList<>();
        private RecordingClient() { super("test", 1, "https://example.invalid/sparql"); }
        @Override public List<WikidataBinding> query(String sparql) {
            queries.add(sparql);
            return List.of();
        }
    }
}
