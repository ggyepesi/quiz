package wikidata.explore.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dataset.DomainStorage;
import datasource.graph.GraphDiscoveryState;
import process.ProcessContext;
import process.ProcessOutcome;
import process.ProcessStatus;
import wikidata.explore.generation.CompiledPipelineRun;
import wikidata.explore.generation.DomainSave;
import wikidata.explore.generation.GenerateDomainProcess;
import wikidata.explore.generation.GenerationExecutionSettings;
import wikidata.explore.generation.GenerationRun;
import wikidata.explore.generation.GenerationRuns;
import wikidata.explore.generation.GraphApplication;
import wikidata.explore.generation.GraphDiscoveryResultStore;
import wikidata.explore.generation.GraphResults;
import wikidata.explore.generation.PipelineRequest;
import wikidata.explore.generation.ProjectLoad;
import wikidata.explore.generation.ProjectSave;
import wikidata.explore.model.BuildOperation;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.logical.ConfiguredGraphDiscoveryQuery;
import work.CancellationToken;
import work.QueryContext;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Runs a project's saved build without a window (directive 22): the thin coordinator over
 * the elementary operations. It holds no generation, graph or persistence semantics of its
 * own; each step is the same operation the desktop runs.
 *
 * <p>{@link #plan} says, before anything runs, what each step will do and why — a step is
 * reused when its saved output is current, and otherwise runs, and the build's steps are an
 * ordered list, so every step after one that runs runs too, because its input changed.
 * {@link #run} carries the plan out. It stops without saving at a graph result awaiting a
 * decision, at an incomplete or failed generation, and at any warning a save raises: an
 * unattended build never replaces the last complete output on doubt. Every build writes a
 * manifest naming each step's state, what was done, and every file written.
 */
public final class ProjectBuild {

    private ProjectBuild() { }

    /** One output's state, as the design names them. RUNNING is never stored. */
    public enum State { MISSING, CURRENT, STALE, AWAITING_DECISION, FAILED, INCOMPLETE }

    /** What became of a step in a run. */
    public enum Action { RAN, REUSED, STOPPED, NOT_REACHED }

    /** A step of the plan: the operation, its state, and the reasons, outermost first. */
    public record Step(BuildOperation operation, String description, State state,
                       List<String> because) {
        public Step {
            because = List.copyOf(because);
        }

        public boolean runs() { return state == State.MISSING || state == State.STALE; }

        public String explain() {
            return description + ": " + state + (because.isEmpty()
                    ? "" : " — because " + String.join(", because ", because));
        }
    }

    /** A step as a run left it. */
    public record Done(Step planned, Action action, String detail) { }

    /** What a build did: each step, the build's outcome, the files written, and where its
     *  manifest is. */
    public record Report(List<Done> steps, State outcome, List<String> files, File manifest) { }

    /**
     * What a build of {@code model} would do now. Reads the saved snapshot's record, the
     * saved graph results and the model; reaches no network and writes nothing.
     */
    public static List<Step> plan(GeneratedProjectModel model, DomainStorage storage)
            throws Exception {
        Saved saved = Saved.read(model, storage, ignored -> { });
        return plan(model, storage, saved);
    }

    /** The saved state a build starts from: the snapshot read onto the current model, and
     *  the graph results restored beside it. */
    private record Saved(ProjectLoad.Projection projection, GraphResults results) {
        static Saved read(GeneratedProjectModel model, DomainStorage storage,
                          Consumer<String> say) throws Exception {
            File snapshot = storage.snapshotFile(model.name());
            ProjectLoad.Projection projection = snapshot.isFile()
                    ? ProjectLoad.project(model, storage, snapshot) : null;
            GraphResults results = new GraphResults(model, storage);
            results.restore(projection == null ? List.of() : projection.objects(), say);
            return new Saved(projection, results);
        }

        List<wikidata.explore.extract.WikidataDynamicObject> objects() {
            return projection == null ? List.of() : projection.objects();
        }
    }

    private static List<Step> plan(GeneratedProjectModel model, DomainStorage storage,
                                   Saved saved) {
        Objects.requireNonNull(model, "model");
        List<Step> steps = new ArrayList<>();
        String upstream = null;
        for (BuildOperation operation : model.buildOperations()) {
            String description = operation.describe(model);
            Step step = upstream == null
                    ? own(operation, description, model, storage, saved)
                    : new Step(operation, description, State.STALE,
                            List.of(upstream + " runs"));
            if (upstream == null && step.runs()) upstream = description;
            steps.add(step);
        }
        return List.copyOf(steps);
    }

    /** A step's state from its own saved output, as if nothing before it ran. */
    private static Step own(BuildOperation operation, String description,
                            GeneratedProjectModel model, DomainStorage storage, Saved saved) {
        GraphResults results = saved.results();
        List<String> because = new ArrayList<>();
        State state;
        switch (operation.kind()) {
            case GENERATE_PROJECT -> {
                File snapshot = storage.snapshotFile(model.name());
                String recorded = storage.savedSnapshotSignature(model.name());
                String now = DomainSave.signature(model);
                Map<String, List<String>> pending =
                        results.pendingPopulationAdditions(saved.objects());
                if (!snapshot.isFile()) {
                    state = State.MISSING;
                    because.add("no snapshot at " + snapshot.getPath());
                } else if (recorded.isBlank() || !recorded.equals(now)) {
                    state = State.STALE;
                    because.add(recorded.isBlank()
                            ? "the saved snapshot does not record the model it came from"
                            : "the model changed since the snapshot was generated");
                } else if (!pending.isEmpty()) {
                    state = State.STALE;
                    because.add(pending.values().stream().mapToInt(List::size).sum()
                            + " accepted graph identities are not generated yet");
                } else {
                    state = State.CURRENT;
                }
            }
            case RUN_GRAPH_CONSTRAINT -> {
                GeneratedClassModel graph = operation.target(model);
                GraphDiscoveryResultStore.Artifact result = results.of(graph);
                if (graph == null) {
                    state = State.FAILED;
                    because.add("the project no longer declares that graph");
                } else if (result == null) {
                    state = State.MISSING;
                    because.add("no saved result for " + graph.className());
                } else if (!result.configurationSignature().equals(
                        GraphResults.configurationSignature(model, graph))) {
                    state = State.STALE;
                    because.add(result.configurationSignature().isBlank()
                            ? "the saved result does not record its configuration"
                            : "the configuration of " + graph.className() + " changed");
                } else {
                    state = State.CURRENT;
                }
            }
            case APPLY_GRAPH_DECISIONS -> {
                GeneratedClassModel graph = operation.target(model);
                GraphDiscoveryResultStore.Artifact result = results.of(graph);
                if (graph == null) {
                    state = State.FAILED;
                    because.add("the project no longer declares that graph");
                } else if (result == null) {
                    state = State.MISSING;
                    because.add("no result of " + graph.className() + " to apply");
                } else if (!result.awaitingDecision().isEmpty()) {
                    state = State.AWAITING_DECISION;
                    because.add(result.awaitingDecision().size()
                            + " Review entries of " + graph.className()
                            + " have no manual decision");
                } else if (!result.applied()) {
                    state = State.MISSING;
                    because.add("the result of " + graph.className() + " is not applied");
                } else {
                    state = State.CURRENT;
                }
            }
            case SAVE_PROJECT_RESULT -> state = State.CURRENT;
            default -> throw new IllegalStateException("Unknown operation " + operation.kind());
        }
        return new Step(operation, description, state, because);
    }

    /**
     * Builds {@code model}: plans, then runs each step that is not current, in order.
     * {@code context} must carry the Wikidata access generation and graph runs use;
     * {@code log} receives every step's report.
     */
    public static Report run(GeneratedProjectModel model, DomainStorage storage,
                             QueryContext context, Consumer<String> log) throws Exception {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(storage, "storage");
        Consumer<String> say = log == null ? ignored -> { } : log;
        File snapshot = storage.snapshotFile(model.name());
        Saved saved = Saved.read(model, storage, say);
        GraphResults results = saved.results();
        GraphDiscoveryState ledger = saved.projection() == null ? GraphDiscoveryState.EMPTY
                : saved.projection().graphDiscovery();

        List<Step> plan = plan(model, storage, saved);
        plan.forEach(step -> say.accept("Plan: " + step.explain()));
        List<Done> done = new ArrayList<>();
        List<String> files = new ArrayList<>();
        GenerationRun run = null;
        State outcome = State.CURRENT;
        for (Step step : plan) {
            if (outcome != State.CURRENT) {
                done.add(new Done(step, Action.NOT_REACHED, "the build stopped before it"));
                continue;
            }
            if (step.state() == State.FAILED) {
                outcome = State.FAILED;
                done.add(new Done(step, Action.STOPPED, String.join("; ", step.because())));
                continue;
            }
            if (step.state() == State.CURRENT) {
                done.add(new Done(step, Action.REUSED, "current"));
                continue;
            }
            BuildOperation operation = step.operation();
            say.accept("Run: " + step.description());
            switch (operation.kind()) {
                case GENERATE_PROJECT -> {
                    Map<String, List<String>> additions = results.pendingPopulationAdditions(
                            run != null ? run.dynamicObjects() : saved.objects());
                    ProcessOutcome<GenerationRun> generated = new GenerateDomainProcess(
                            CompiledPipelineRun.compile(PipelineRequest.generateDomain(
                                    model.copy(), additions)),
                            null, new GenerationExecutionSettings(), null)
                            .execute(new ProcessContext(context, null, null,
                                    new CancellationToken(), null));
                    if (generated.status() != ProcessStatus.SUCCEEDED
                            || generated.result() == null) {
                        outcome = generated.status() == ProcessStatus.PARTIAL
                                ? State.INCOMPLETE : State.FAILED;
                        done.add(new Done(step, Action.STOPPED, generated.status() + ": "
                                + (generated.error() == null
                                        ? generated.summary() : generated.error().getMessage())));
                        continue;
                    }
                    run = replace(run, generated.result());
                    ledger = GenerationRuns.ledgerAfter(ledger, run);
                    done.add(new Done(step, Action.RAN, run.dynamicObjects().size() + " objects"));
                }
                case RUN_GRAPH_CONSTRAINT -> {
                    run = run != null ? run : loaded(model, storage, snapshot, say);
                    GeneratedClassModel graph = operation.target(model);
                    GeneratedProjectModel copy = model.copy();
                    ConfiguredGraphDiscoveryQuery.Result result = new ConfiguredGraphDiscoveryQuery(
                            copy, copy.findClass(graph.className()), run.instances())
                            .execute(context);
                    GraphDiscoveryResultStore.Artifact artifact = GraphDiscoveryResultStore
                            .artifact(model.name(), graph.className(), result);
                    results.record(graph, artifact);
                    done.add(new Done(step, Action.RAN, artifact.instances().size()
                            + " annotations, " + artifact.acceptedIdentities().size()
                            + " accepted"));
                }
                case APPLY_GRAPH_DECISIONS -> {
                    run = run != null ? run : loaded(model, storage, snapshot, say);
                    GeneratedClassModel graph = operation.target(model);
                    GraphDiscoveryResultStore.Artifact result = results.of(graph);
                    if (result == null) {
                        outcome = State.FAILED;
                        done.add(new Done(step, Action.STOPPED,
                                "no result of " + graph.className() + " to apply"));
                        continue;
                    }
                    GraphApplication.Outcome applied = GraphApplication.apply(result, model, run);
                    if (applied instanceof GraphApplication.AwaitingDecision waiting) {
                        outcome = State.AWAITING_DECISION;
                        done.add(new Done(step, Action.STOPPED, waiting.message()));
                        continue;
                    }
                    var success = (GraphApplication.Applied) applied;
                    run = replace(run, success.run());
                    results.markApplied(graph, success.result());
                    done.add(new Done(step, Action.RAN, success.message(model.isModel())));
                }
                case SAVE_PROJECT_RESULT -> {
                    ProjectSave save = ProjectSave.plan(new ProjectSave.Input(model,
                            run == null ? null : new ProjectSave.Run(run.dynamicObjects(),
                                    run.loadedDeclarations(),
                                    run.selfReferenceAudit().ledger(), run.modelSnapshot(),
                                    run.generatedFromSignature()),
                            ledger, results.all()), storage);
                    if (!save.warnings().isEmpty()) {
                        outcome = State.FAILED;
                        done.add(new Done(step, Action.STOPPED, "save refused: "
                                + save.warnings().stream().map(ProjectSave.Warning::message)
                                        .reduce((a, b) -> a + " " + b).orElse("")));
                        continue;
                    }
                    ProjectSave.Result written = save.write();
                    files.addAll(written.report());
                    done.add(new Done(step, Action.RAN,
                            written.instancesWritten() + " instances saved"));
                }
                default -> throw new IllegalStateException("Unknown operation " + operation.kind());
            }
        }
        if (run != null && run.runtime() != null) run.runtime().close();
        File manifest = writeManifest(storage, model, done, outcome, files);
        done.forEach(step -> say.accept(step.action() + ": " + step.planned().description()
                + (step.detail().isBlank() ? "" : " — " + step.detail())));
        say.accept("Build " + outcome + "; manifest " + manifest.getPath());
        return new Report(List.copyOf(done), outcome, List.copyOf(files), manifest);
    }

    /** Hands over to {@code next}, closing the compiled runtime nothing will use again. */
    private static GenerationRun replace(GenerationRun previous, GenerationRun next) {
        return GenerationRuns.handOver(previous, next);
    }

    private static GenerationRun loaded(GeneratedProjectModel model, DomainStorage storage,
                                        File snapshot, Consumer<String> say) throws Exception {
        if (!snapshot.isFile()) {
            throw new IllegalStateException("No instances to run on: " + snapshot.getPath()
                    + " does not exist. Put Generate project before this step.");
        }
        return ProjectLoad.load(model, storage, snapshot, say).run();
    }

    private static File writeManifest(DomainStorage storage, GeneratedProjectModel model,
                                      List<Done> done, State outcome, List<String> files)
            throws Exception {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("project", model.name());
        manifest.put("builtAt", java.time.LocalDateTime.now().withNano(0).toString());
        manifest.put("outcome", outcome.name());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Done step : done) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("operation", step.planned().operation().declarationId());
            row.put("step", step.planned().description());
            row.put("state", step.planned().state().name());
            row.put("because", step.planned().because());
            row.put("action", step.action().name());
            row.put("detail", step.detail());
            steps.add(row);
        }
        manifest.put("steps", steps);
        manifest.put("files", files);
        File file = storage.buildManifestFile(model.name());
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(file, manifest);
        return file;
    }
}
