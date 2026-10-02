package wikidata.explore.build;

import dataset.DomainStorage;
import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;
import wikidata.explore.query.core.QueryFactory;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Builds a saved project from the command line, with no window (directive 22):
 *
 * <pre>
 *   build &lt;project&gt;          run the project's saved build
 *   build &lt;project&gt; --plan   say what a build would do, and why, without doing it
 * </pre>
 *
 * <p>Run from the repository root, where the data directory is. The exit code says how
 * the build ended: 0 every step current, 3 awaiting a decision, 4 incomplete, 1 failed,
 * 2 a usage problem. A stopped build replaces nothing the last complete build saved.
 */
public final class BuildMain {

    private static final String USER_AGENT = "quiz-build/1.0 (ggyepesi@gmail.com)";

    private BuildMain() { }

    public static void main(String[] args) throws Exception {
        DomainStorage storage = DomainStorage.inDefaultLocation();
        try (WikidataSparqlClient sparql = new WikidataSparqlClient(USER_AGENT, 4);
             QueryFactory queries = new QueryFactory(
                     sparql, new WikidataApiClient(USER_AGENT), USER_AGENT)) {
            System.exit(run(args, storage, () -> queries.newContext(
                    Path.of(aux.Constants.wikidataDataDirectory)), System.out));
        }
    }

    /** The command, with its storage, its Wikidata access and its output as parameters. */
    static int run(String[] args, DomainStorage storage,
                   Supplier<work.QueryContext> context, PrintStream out) throws Exception {
        List<String> arguments = args == null ? List.of() : List.of(args);
        List<String> names = arguments.stream().filter(arg -> !arg.startsWith("--")).toList();
        boolean planOnly = arguments.contains("--plan");
        if (names.size() != 1) {
            out.println("Usage: build <project> [--plan]");
            return 2;
        }
        File modelFile = storage.modelFileOf(names.getFirst());
        if (modelFile == null || !modelFile.isFile()) {
            out.println("No saved project \"" + names.getFirst() + "\" under "
                    + storage.registryFile().getParentFile().getPath());
            return 2;
        }
        out.println("Reading model " + modelFile.getPath());
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(modelFile);
        if (model.buildOperations().isEmpty()) {
            out.println("\"" + model.name() + "\" has no build. Add its steps in ModelBuilder's "
                    + "project overview (Build), then save the project.");
            return 2;
        }
        if (planOnly) {
            ProjectBuild.plan(model, storage).forEach(step -> out.println(step.explain()));
            return 0;
        }
        ProjectBuild.Report report = ProjectBuild.run(model, storage, context.get(), out::println);
        return exitCode(report.outcome());
    }

    static int exitCode(ProjectBuild.State outcome) {
        return switch (outcome) {
            case CURRENT -> 0;
            case AWAITING_DECISION -> 3;
            case INCOMPLETE -> 4;
            default -> 1;
        };
    }
}
