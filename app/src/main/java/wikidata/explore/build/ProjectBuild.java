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
import wikidata.explore.model.GeneratedProjectModelStore;
import wikidata.explore.model.PopulationSelection;
import wikidata.explore.transform.MaterializedPopulationStore;
import wikidata.explore.transform.TransformationExecution;
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
 * Runs a project's saved build without a window (directive 23): the thin coordinator over
 * the elementary operations. It holds no generation, graph or persistence semantics of its
 * own; each step is the same operation the desktop runs.
 *
 * <p>{@link #plan} says, before anything runs, what each step will do and why — a step is
 * reused when its saved output is current, and otherwise runs, and the build's steps are an
 * ordered list, so every step after one that runs runs too, because its input changed.
 * {@link #run} carries the plan out. It stops without saving at a graph result awaiting a
 * decision, at an incomplete or failed generation, and at any warning a save raises: an
 * unattended build never replaces the last complete output on doubt. A graph run's
 * annotations are saved as soon as they exist, beside the snapshot, so a stop leaves them
 * to be decided. Every build writes a manifest naming each step's state, what was done,
 * and every file written.
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
        return plan(model, storage, saved, new java.util.LinkedHashSet<>());
    }

    private static List<Step> plan(GeneratedProjectModel model, DomainStorage storage,
                                   Saved saved, java.util.Set<String> planning) {
        Objects.requireNonNull(model, "model");
        planning.add(model.name());
        List<Step> steps = new ArrayList<>();
        String upstream = null;
        Map<String, String> misordered = orderProblems(model);
        for (BuildOperation operation : model.buildOperations()) {
            String description = operation.describe(model);
            String problem = misordered.get(operation.declarationId());
            Step step = problem != null
                    ? new Step(operation, description, State.FAILED, List.of(problem))
                    : upstream == null
                    ? own(operation, description, model, storage, saved, planning)
                    : new Step(operation, description, State.STALE,
                            List.of(upstream + " runs"));
            if (upstream == null && step.runs()) upstream = description;
            steps.add(step);
        }
        planning.remove(model.name());
        return List.copyOf(steps);
    }

    /**
     * Steps that would read a membership an earlier step has silently discarded, keyed by
     * operation declaration id. Generate replaces the whole run and carries forward only
     * graph results that ADD members (as pending generation input), so it discards every
     * narrowing applied before it; a transformation rebuilds its output class from its
     * input, so it discards every graph application to that class. Until the discarded
     * application is applied again, a later transformation, graph run, publication or save
     * reads the wrong population — Historical Positions once filtered the whole Position
     * population because its second Generate undid GraphConstraint's narrowing.
     */
    static Map<String, String> orderProblems(GeneratedProjectModel model) {
        Map<String, String> problems = new LinkedHashMap<>();
        Map<String, String> applied = new LinkedHashMap<>();   // graph id -> its Apply step
        Map<String, String> discarded = new LinkedHashMap<>(); // graph id -> why
        for (BuildOperation operation : model.buildOperations()) {
            String description = operation.describe(model);
            GeneratedClassModel target = operation.target(model);
            switch (operation.kind()) {
                case GENERATE_PROJECT -> applied.entrySet().removeIf(entry -> {
                    GeneratedClassModel graph = model.findClassById(entry.getKey());
                    if (graph == null || GraphResults.populationOperation(graph.graphSource())
                            != datasource.graph.GraphDiscoveryConfiguration
                                    .PopulationOperation.NARROW) {
                        return false;
                    }
                    discarded.put(entry.getKey(), description + " discards the narrowing "
                            + "applied by " + entry.getValue());
                    return true;
                });
                case APPLY_GRAPH_DECISIONS -> {
                    if (target == null) break;
                    applied.put(target.declarationId(), description);
                    discarded.remove(target.declarationId());
                }
                case RUN_TRANSFORMATION, RUN_GRAPH_CONSTRAINT, PUBLISH_POPULATION,
                     SAVE_PROJECT_RESULT -> {
                    java.util.List<String> reasons = discarded.entrySet().stream()
                            .filter(entry -> operation.kind()
                                    != BuildOperation.Kind.RUN_GRAPH_CONSTRAINT
                                    || target == null
                                    || !entry.getKey().equals(target.declarationId()))
                            .map(entry -> entry.getValue() + "; apply "
                                    + model.findClassById(entry.getKey()).className()
                                    + " again before this step")
                            .toList();
                    if (!reasons.isEmpty()) {
                        problems.put(operation.declarationId(), String.join("; ", reasons));
                    }
                    if (operation.kind() == BuildOperation.Kind.RUN_TRANSFORMATION
                            && target != null) {
                        applied.entrySet().removeIf(entry -> {
                            GeneratedClassModel graph = model.findClassById(entry.getKey());
                            if (graph == null || graph.graphSource() == null
                                    || !target.className().equalsIgnoreCase(
                                            graph.graphSource().outputClassName())) {
                                return false;
                            }
                            discarded.put(entry.getKey(), description + " rebuilds "
                                    + target.className() + " and discards " + entry.getValue());
                            return true;
                        });
                    }
                }
                case BUILD_DEPENDENCY -> { }
            }
        }
        return problems;
    }

    /** A step's state from its own saved output, as if nothing before it ran. */
    private static Step own(BuildOperation operation, String description,
                            GeneratedProjectModel model, DomainStorage storage, Saved saved,
                            java.util.Set<String> planning) {
        GraphResults results = saved.results();
        List<String> because = new ArrayList<>();
        State state;
        switch (operation.kind()) {
            case BUILD_DEPENDENCY -> {
                if (planning.contains(operation.targetProjectName())) {
                    state = State.FAILED;
                    because.add("build dependency cycle: " + String.join(" -> ", planning)
                            + " -> " + operation.targetProjectName());
                    break;
                }
                File dependency = storage.modelFileOf(operation.targetProjectName());
                if (!dependency.isFile()) {
                    state = State.FAILED;
                    because.add("model file is missing: " + dependency.getPath());
                    break;
                }
                try {
                    GeneratedProjectModel upstream =
                            new GeneratedProjectModelStore().load(dependency);
                    Saved upstreamSaved = Saved.read(upstream, storage, ignored -> { });
                    List<Step> upstreamPlan = plan(
                            upstream, storage, upstreamSaved, planning);
                    Step blocked = upstreamPlan.stream()
                            .filter(item -> item.state() == State.FAILED
                                    || item.state() == State.INCOMPLETE
                                    || item.state() == State.AWAITING_DECISION)
                            .findFirst().orElse(null);
                    if (blocked != null) {
                        state = blocked.state();
                        because.add(upstream.name() + ": " + blocked.explain());
                    } else if (upstreamPlan.stream().anyMatch(Step::runs)) {
                        state = State.STALE;
                        because.add(upstream.name() + " has missing or stale outputs");
                    } else {
                        state = State.CURRENT;
                    }
                } catch (Exception unreadable) {
                    state = State.FAILED;
                    because.add("cannot inspect " + operation.targetProjectName()
                            + ": " + unreadable.getMessage());
                }
            }
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
            case RUN_TRANSFORMATION -> {
                GeneratedClassModel outputClass = operation.target(model);
                if (outputClass == null || !outputClass.materializedByTransformation()
                        || outputClass.transformationSource() == null) {
                    state = State.FAILED;
                    because.add("the project no longer declares that materialized class");
                    break;
                }
                File output = storage.materializedPopulationFile(
                        model.name(), outputClass.declarationId());
                MaterializedPopulationStore.Metadata metadata =
                        MaterializedPopulationStore.metadata(model, outputClass, storage);
                if (!output.isFile() || metadata == null) {
                    state = State.MISSING;
                    because.add("no materialized population at " + output.getPath());
                } else if (!MaterializedPopulationStore.signature(outputClass)
                        .equals(metadata.transformationSignature())) {
                    state = State.STALE;
                    because.add("the saved transformation changed");
                } else if (!storage.savedSnapshotSignature(model.name())
                        .equals(metadata.inputSnapshotSignature())) {
                    state = State.STALE;
                    because.add("the generated input snapshot changed");
                } else {
                    state = State.CURRENT;
                }
            }
            case PUBLISH_POPULATION -> {
                if (!(operation.selectionTarget(model) instanceof PopulationSelection population)) {
                    state = State.FAILED;
                    because.add("the project no longer declares that population selection");
                    break;
                }
                GeneratedClassModel outputClass = model.findClass(population.className());
                if (outputClass == null || !outputClass.materializedByTransformation()
                        || outputClass.transformationSource() == null) {
                    state = State.FAILED;
                    because.add("the population's class has no transformation producer");
                    break;
                }
                File output = storage.publishedPopulationFile(
                        model.name(), population.declarationId());
                MaterializedPopulationStore.Metadata metadata =
                        MaterializedPopulationStore.publication(model, population, storage);
                if (!output.isFile() || metadata == null) {
                    state = State.MISSING;
                    because.add("no published population at " + output.getPath());
                } else if (!MaterializedPopulationStore.signature(outputClass)
                        .equals(metadata.transformationSignature())) {
                    state = State.STALE;
                    because.add("the saved transformation changed");
                } else if (!storage.savedSnapshotSignature(model.name())
                        .equals(metadata.inputSnapshotSignature())) {
                    state = State.STALE;
                    because.add("the generated input snapshot changed");
                } else {
                    try {
                        List<String> qids = MaterializedPopulationStore
                                .loadPublished(model, population, storage).stream()
                                .map(wikidata.explore.extract.WikidataDynamicObject::getIdentifier)
                                .filter(java.util.Objects::nonNull).distinct().toList();
                        if (!population.instanceQids().equals(qids)) {
                            state = State.STALE;
                            because.add("the selection's QID list differs from its publication");
                        } else {
                            state = State.CURRENT;
                        }
                    } catch (Exception unreadable) {
                        state = State.FAILED;
                        because.add("the published population cannot be read: "
                                + unreadable.getMessage());
                    }
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
        return run(model, storage, context, log, new java.util.LinkedHashSet<>());
    }

    private static Report run(GeneratedProjectModel model, DomainStorage storage,
                             QueryContext context, Consumer<String> log,
                             java.util.Set<String> building) throws Exception {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(storage, "storage");
        if (!building.add(model.name())) {
            throw new IllegalStateException("Build dependency cycle: "
                    + String.join(" -> ", building) + " -> " + model.name());
        }
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
        // A misordered build is refused before any step runs: running the steps before the
        // misordered one would spend a Wikidata generation on a result it cannot use.
        State outcome = orderProblems(model).isEmpty() ? State.CURRENT : State.FAILED;
        if (outcome == State.FAILED) {
            say.accept("Refused: the build order discards an applied graph result; "
                    + "nothing was run.");
        }
        for (Step step : plan) {
            if (step.state() == State.FAILED) {
                outcome = State.FAILED;
                done.add(new Done(step, Action.STOPPED, String.join("; ", step.because())));
                continue;
            }
            if (outcome != State.CURRENT) {
                done.add(new Done(step, Action.NOT_REACHED, "the build stopped before it"));
                continue;
            }
            if (step.state() == State.CURRENT) {
                done.add(new Done(step, Action.REUSED, "current"));
                continue;
            }
            BuildOperation operation = step.operation();
            say.accept("Run: " + step.description());
            switch (operation.kind()) {
                case BUILD_DEPENDENCY -> {
                    File dependency = storage.modelFileOf(operation.targetProjectName());
                    GeneratedProjectModel upstream =
                            new GeneratedProjectModelStore().load(dependency);
                    Report built = run(upstream, storage, context, say, building);
                    files.addAll(built.files());
                    if (built.outcome() != State.CURRENT) {
                        outcome = built.outcome();
                        done.add(new Done(step, Action.STOPPED, upstream.name()
                                + " build ended " + built.outcome()));
                        continue;
                    }
                    done.add(new Done(step, Action.RAN, upstream.name()
                            + " is current; manifest " + built.manifest().getPath()));
                }
                case GENERATE_PROJECT -> {
                    Map<String, List<String>> additions = results.pendingPopulationAdditions(
                            run != null ? run.dynamicObjects() : saved.objects());
                    ProcessOutcome<GenerationRun> generated = new GenerateDomainProcess(
                            CompiledPipelineRun.compile(PipelineRequest.generateDomain(
                                    model.copy(), additions)),
                            null, new GenerationExecutionSettings(), null)
                            .execute(new ProcessContext(
                                    context.with(DomainStorage.class, storage), null, null,
                                    context.cancellation() == null
                                            ? new CancellationToken() : context.cancellation(),
                                    null));
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
                case RUN_TRANSFORMATION -> {
                    run = run != null ? run : loaded(model, storage, snapshot, say);
                    GeneratedClassModel outputClass = operation.target(model);
                    TransformationExecution.Result transformed =
                            TransformationExecution.apply(model, outputClass, run);
                    run = replace(run, transformed.run());
                    List<String> written = MaterializedPopulationStore.save(
                            model, outputClass, transformed.population(), storage,
                            run.generatedFromSignature());
                    files.addAll(written);
                    done.add(new Done(step, Action.RAN, transformed.population().size()
                            + " " + outputClass.className()
                            + " instances -> " + written.getFirst()));
                }
                case PUBLISH_POPULATION -> {
                    PopulationSelection population =
                            (PopulationSelection) operation.selectionTarget(model);
                    GeneratedClassModel outputClass = model.findClass(population.className());
                    run = run != null ? run : loaded(model, storage, snapshot, say);
                    List<wikidata.explore.extract.WikidataDynamicObject> published =
                            run.dynamicObjects().stream()
                                    .filter(object -> object.directClassNames().contains(
                                            population.className()))
                                    .toList();
                    files.addAll(MaterializedPopulationStore.publish(
                            model, population, outputClass, published, storage,
                            run.generatedFromSignature()));
                    List<String> qids = published.stream()
                            .map(wikidata.explore.extract.WikidataDynamicObject::getIdentifier)
                            .filter(java.util.Objects::nonNull).distinct().toList();
                    population.instanceQids(qids);
                    done.add(new Done(step, Action.RAN, qids.size() + " "
                            + population.className() + " QIDs published as "
                            + population.name()));
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
                    // A graph run's annotations are an output of their own, saved beside
                    // the snapshot rather than in it. Saved now, a build that then stops
                    // awaiting a decision leaves them on disk to be decided, while the
                    // snapshot — the last complete output — is untouched.
                    files.add(GraphDiscoveryResultStore.save(results.of(graph), storage));
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
        building.remove(model.name());
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
