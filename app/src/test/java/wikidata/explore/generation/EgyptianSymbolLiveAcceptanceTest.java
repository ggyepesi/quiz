package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import process.ProcessContext;
import process.ProcessOutcome;
import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;
import wikidata.explore.query.core.QueryFactory;
import work.CancellationToken;
import work.LogKind;
import work.LogNode;
import work.WorkflowRecorder;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in network acceptance over the complete saved Egyptian Gods configuration.
 * The ordinary suite stays deterministic; scripts/accept-egyptian-symbol-live.sh is
 * the explicit command that starts from an empty temporary data/cache directory.
 */
@EnabledIfEnvironmentVariable(named = "QUIZ_LIVE_EGYPTIAN_SYMBOL", matches = "true")
class EgyptianSymbolLiveAcceptanceTest {
    private static final String USER_AGENT =
            "quiz-egyptian-symbol-acceptance/1.0 (ggyepesi@gmail.com)";

    @Test void cleanGenerationFindsTheBoundedPopulationAndWikipediaSymbols(
            @TempDir Path cleanData) throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(
                Path.of("../data/wikidata/egyptiangods/egyptiangods.model.json").toFile());
        GenerationExecutionSettings settings = new GenerationExecutionSettings(false);
        settings.requireComplete(false);
        CompiledPipelineRun compiled = CompiledPipelineRun.compile(
                PipelineRequest.generateDomain(model));
        WorkflowRecorder recorder = new WorkflowRecorder(
                new LogNode(LogKind.WORKFLOW, "EgyptianDeity.symbol live acceptance"));

        try (WikidataSparqlClient sparql = new WikidataSparqlClient(USER_AGENT, 4);
             QueryFactory queries = new QueryFactory(
                     sparql, new WikidataApiClient(USER_AGENT), USER_AGENT)) {
            ProcessContext context = new ProcessContext(
                    queries.newContext(cleanData), recorder, null,
                    new CancellationToken(), null);
            ProcessOutcome<GenerationRun> outcome = context.run(
                    new GenerateDomainProcess(compiled,
                            GenerateDomainPipeline.configured(compiled), settings));
            GenerationRun run = outcome.result();
            assertNotNull(run, () -> outcome.status() + ": " + outcome.summary());

            var deities = run.dynamicObjects().stream()
                    .filter(value -> value.directClassNames().contains("EgyptianDeity"))
                    .toList();
            long identities = deities.stream().map(value -> value.qid())
                    .filter(qid -> !qid.isBlank()).distinct().count();
            long symbols = deities.stream().filter(value -> {
                Object symbol = value.get("symbol");
                return symbol != null && !symbol.toString().isBlank();
            }).count();
            long examinedEmpty = deities.stream()
                    .filter(value -> value.renderedInfoboxAnswered()
                            && value.get("symbol") == null)
                    .count();

            System.out.println("EgyptianDeity identities=" + identities
                    + ", symbol values=" + symbols
                    + ", examined without symbol=" + examinedEmpty
                    + ", run=" + outcome.status());
            assertEquals(156, identities,
                    "the explicitly configured population has drifted");
            assertTrue(symbols > 0, "Wikipedia supplied no configured symbol values");
            assertTrue(examinedEmpty > 0,
                    "the run did not retain the examined-empty outcome");

            LogNode generation = descendants(recorder.root()).stream()
                    .filter(node -> "Generate domain \"Egyptian Gods\"".equals(node.title()))
                    .findFirst().orElseThrow(() -> new AssertionError(
                            "the generation run has no structured domain log node"));
            assertTrue(descendants(generation).stream().anyMatch(node ->
                            node.title() != null
                                    && node.title().startsWith("Root membership")),
                    "population acquisition is not child work of the generation run");
            assertTrue(descendants(generation).stream().anyMatch(node ->
                            node.title() != null && node.title().startsWith(
                                    "Acquire native Wikipedia infoboxes")),
                    "Wikipedia acquisition is not child work of the generation run");
        }
    }

    private static java.util.List<LogNode> descendants(LogNode root) {
        java.util.List<LogNode> result = new java.util.ArrayList<>();
        java.util.ArrayDeque<LogNode> pending = new java.util.ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            LogNode next = pending.removeFirst();
            result.add(next);
            pending.addAll(next.steps());
        }
        return result;
    }
}
