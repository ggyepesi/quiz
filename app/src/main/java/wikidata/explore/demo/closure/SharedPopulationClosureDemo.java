package wikidata.explore.demo.closure;

import batch.BatchPolicy;
import batch.BatchProgress;
import batch.InMemoryBatchCheckpointStore;
import wikidata.WikidataBatchFailureClassifier;
import wikidata.WikidataSparqlClient;
import work.CancellationToken;

import java.net.http.HttpClient;
import java.util.List;

/**
 * Console demonstration of checkpointed map/reduce-style graph closure.
 *
 * <p>Defaults to people who held "Apostolic King of Hungary" and follows their other
 * positions. Arguments are: start QID, population PID, next-wave PID, max depth,
 * batch size, max discovered values.
 */
public final class SharedPopulationClosureDemo {
    private static final String USER_AGENT =
            "QuizProject/1.0 (https://github.com/ggyepesi/quiz)";

    private SharedPopulationClosureDemo() { }

    public static void main(String[] args) throws Exception {
        SharedPopulationClosureConfig defaults =
                SharedPopulationClosureConfig.apostolicKingsOfHungary();
        SharedPopulationClosureConfig config = new SharedPopulationClosureConfig(
                arg(args, 0, defaults.startValueQid()),
                arg(args, 1, defaults.populationPropertyPid()),
                arg(args, 2, defaults.nextWavePropertyPid()),
                intArg(args, 3, defaults.maxDepth()),
                intArg(args, 4, defaults.batchSize()),
                intArg(args, 5, defaults.maxValues()),
                qidArgs(args, 6, defaults.targetRootQids()));

        System.out.println("Shared-population closure");
        System.out.println("  start value:        " + config.startValueQid());
        System.out.println("  population property:" + config.populationPropertyPid());
        System.out.println("  next-wave property: " + config.nextWavePropertyPid());
        System.out.println("  maximum depth:      " + config.maxDepth());
        System.out.println("  batch size:         " + config.batchSize());
        System.out.println("  maximum values:     " + config.maxValues());
        System.out.println("  memberships/unit:   " + config.maxMembershipsPerUnit());
        System.out.println("  connections/unit:   " + config.maxConnectionsPerUnit());
        System.out.println("  allowed roots:      " + config.targetRootQids());

        InMemoryBatchCheckpointStore journal = new InMemoryBatchCheckpointStore();
        try (WikidataSparqlClient client = new WikidataSparqlClient(
                USER_AGENT,
                1,
                WikidataSparqlClient.WIKIDATA_ENDPOINT,
                HttpClient.Version.HTTP_1_1)) {
            client.minRequestSpacingMillis(250);
            BatchProgress progress = consoleProgress();
            CancellationToken cancellation = new CancellationToken();
            SharedPopulationClosure closure = new SharedPopulationClosure(
                    config,
                    new SparqlSharedPopulationExpansionSource(
                            client, BatchPolicy.defaults(), progress,
                            WikidataBatchFailureClassifier.INSTANCE,
                            cancellation, 1),
                    BatchPolicy.defaults(),
                    progress,
                    WikidataBatchFailureClassifier.INSTANCE,
                    cancellation,
                    journal,
                    1);

            SharedPopulationClosure.Result result = closure.execute();
            printResult(result);
            printJournal(journal);
        }
    }

    private static void printResult(SharedPopulationClosure.Result result) {
        System.out.println("\nDiscovered values:");
        for (SharedPopulationClosure.Value value : result.values()) {
            System.out.printf("  depth=%d  %-10s %s%n",
                    value.minimumDepth(), value.qid(), value.label());
        }
        for (SharedPopulationClosure.Wave wave : result.waves()) {
            System.out.printf(
                    "%nWave %d: frontier=%d, population members=%d, edges=%d, new values=%d, rejected targets=%d%n",
                    wave.depth(), wave.frontier().size(),
                    wave.members().size(), wave.edges().size(), wave.newValues().size(),
                    wave.rejectedTargetCount());
            for (SharedPopulationMember member : wave.members()) {
                System.out.printf("  MEMBER: %s <- %s%n",
                        member.sourceLabel(), member.memberLabel());
            }
            for (SharedPopulationEdge edge : wave.edges()) {
                System.out.printf("  %s --[%s]--> %s%n",
                        edge.sourceLabel(), edge.memberLabel(), edge.targetLabel());
            }
        }
        if (result.valueLimitReached()) {
            System.out.println("\nStopped because the configured value limit was reached.");
        }
        if (result.resourceLimitReached()) {
            System.out.println("\nStopped incomplete because an acquisition limit was reached.");
        }
    }

    private static void printJournal(InMemoryBatchCheckpointStore journal) {
        System.out.println("\nIn-memory checkpoint journal:");
        for (InMemoryBatchCheckpointStore.Event event : journal.events()) {
            String keys = event.work().isEmpty() ? "" : " " + event.work().stream()
                    .map(work -> work.descriptor().key())
                    .toList();
            String target = event.workKey() == null ? "" : " " + event.workKey();
            System.out.println("  " + event.type() + " " + event.runKey()
                    + target + keys);
        }
    }

    private static BatchProgress consoleProgress() {
        return new BatchProgress() {
            @Override public Running started(String title, String request) {
                System.out.println("\nMAP START: " + title);
                return new Running() {
                    @Override public void detail(String text) {
                        System.out.println("  " + text);
                    }
                    @Override public void done(String summary) {
                        System.out.println("MAP COMPLETE: " + title + " — " + summary);
                    }
                    @Override public void failed(String error) {
                        System.out.println("MAP FAILED: " + title + " — " + error);
                    }
                };
            }

            @Override public void message(String text) {
                System.out.print(text);
            }
        };
    }

    private static String arg(String[] args, int index, String fallback) {
        return args.length > index && !args[index].isBlank() ? args[index] : fallback;
    }

    private static int intArg(String[] args, int index, int fallback) {
        return args.length > index ? Integer.parseInt(args[index]) : fallback;
    }

    private static List<String> qidArgs(
            String[] args, int index, List<String> fallback) {
        if (args.length <= index || args[index] == null || args[index].isBlank()) {
            return fallback;
        }
        return List.of(args[index].trim().split("[\\s,;]+"));
    }
}
