package wikidata.explore.generation;

import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.compiled.ProjectModelCompiler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One request, compiled once, before any external work.
 *
 * <p>The model and the phase decisions are settled here and read everywhere: the diagram
 * a reader sees and the execution that follows consume this same object, so they cannot
 * disagree. Five flows previously each answered "which phases, in what order, and may
 * they fetch" for themselves, and the answers drifted.
 *
 * <p>Compiling first is also what turns a malformed model into an explained decision
 * instead of a late exception. A model that will not compile blocks every phase after
 * COMPILE, with the validation report as the reason, before a single request is made.
 */
public record CompiledPipelineRun(
        PipelineRequest request,
        CompiledProjectModel model,
        Map<PipelinePhase, PhaseDecision> decisions) {

    public CompiledPipelineRun {
        decisions = Map.copyOf(decisions);
    }

    /**
     * Decides every phase from the request alone.
     *
     * <p>No flow name reaches this method. Each decision follows from something the
     * request states: what the input already is, whether the scope discovers, whether
     * acquisition is permitted. A {@code generate}-versus-{@code enrich} switch here
     * would be the drift being re-encoded rather than removed.
     */
    public static CompiledPipelineRun compile(PipelineRequest request) {
        if (request == null) throw new IllegalArgumentException("No request to compile");
        Map<PipelinePhase, PhaseDecision> decisions = new LinkedHashMap<>();

        CompiledProjectModel model = null;
        String refusal = "";
        try {
            model = ProjectModelCompiler.compile(request.model());
        } catch (RuntimeException uncompilable) {
            refusal = uncompilable.getMessage() == null
                    ? "The model cannot be compiled." : uncompilable.getMessage();
        }
        decisions.put(PipelinePhase.COMPILE, PhaseDecision.run());
        if (model == null) {
            // Blocked, not failed: the reader is told what is wrong with the model
            // before anything is fetched against it.
            for (PipelinePhase phase : PipelinePhase.values()) {
                if (phase != PipelinePhase.COMPILE) {
                    decisions.put(phase, PhaseDecision.blocked(refusal));
                }
            }
            return new CompiledPipelineRun(request, null, decisions);
        }

        decisions.put(PipelinePhase.STAGE_INPUT_GRAPH, request.input().isEmpty()
                ? PhaseDecision.skip("starting from an empty graph")
                : PhaseDecision.run());

        decisions.put(PipelinePhase.DISCOVER_POPULATION,
                discoverPopulation(request, model));

        decisions.put(PipelinePhase.ACQUIRE_SOURCE_FACTS, request.mayAcquire()
                ? PhaseDecision.run()
                : PhaseDecision.skip("acquisition forbidden"));

        decisions.put(PipelinePhase.CONSTRUCT_RECORDS, constructRecords(request));

        decisions.put(PipelinePhase.RESOLVE_SEMANTIC_WORKLIST, PhaseDecision.run());

        decisions.put(PipelinePhase.ACQUIRE_EXTERNAL_EVIDENCE, request.mayAcquire()
                ? PhaseDecision.run()
                : PhaseDecision.skip("acquisition forbidden"));

        decisions.put(PipelinePhase.REFRESH_DERIVED_VALUES, PhaseDecision.run());

        decisions.put(PipelinePhase.HYDRATE_NAMES, request.mayAcquire()
                ? PhaseDecision.run()
                : PhaseDecision.skip("acquisition forbidden; names stay as stored"));

        decisions.put(PipelinePhase.FINALIZE, PhaseDecision.run());
        decisions.put(PipelinePhase.MATERIALIZE, PhaseDecision.run());

        return new CompiledPipelineRun(request, model, decisions);
    }

    /**
     * A population is discovered unless the run already has one — or cannot have one.
     *
     * <p>A scope naming a class the model does not contain is the request being wrong
     * about the model, which is worth saying before anything is fetched for it.
     */
    private static PhaseDecision discoverPopulation(
            PipelineRequest request, CompiledProjectModel model) {
        if (!request.scope().discovers()) {
            return PhaseDecision.skip("using the existing population");
        }
        String className = request.scope().className();
        if (!className.isEmpty() && model.findClass(className).isEmpty()) {
            return PhaseDecision.blocked(
                    "\"" + className + "\" is not a class of this model");
        }
        return PhaseDecision.run();
    }

    /**
     * Whether records are constructed follows from the graph the run starts from.
     *
     * <p>Not from which flow this is. Generate starts from normalized source data and
     * must reify before anything can see the records; Enrich starts from a graph whose
     * records already exist, and reifying again would build a second copy of every one
     * of them. The checkpoint's stage is the whole answer.
     */
    private static PhaseDecision constructRecords(PipelineRequest request) {
        Optional<GraphCheckpoint> supplied = request.input().suppliedCheckpoint();
        if (supplied.isEmpty()) return PhaseDecision.run();
        return switch (supplied.get().stage()) {
            case NORMALIZED_SOURCE_GRAPH -> PhaseDecision.run();
            case CONSTRUCTED_GRAPH, FINAL_GRAPH ->
                    PhaseDecision.skip("records were constructed before this checkpoint");
        };
    }

    public PhaseDecision decision(PipelinePhase phase) {
        return decisions.getOrDefault(phase, PhaseDecision.run());
    }

    public boolean runs(PipelinePhase phase) {
        return decision(phase).runs();
    }

    /** Whether any phase of this run is impossible; nothing should be attempted then. */
    public boolean blocked() {
        return decisions.values().stream()
                .anyMatch(decision -> decision.status() == PhaseDecision.Status.BLOCKED);
    }

    /**
     * The phase after which derived values must be refreshed.
     *
     * <p>The last producer of field values that actually runs, because an aggregate keyed
     * on a field acquired afterwards is an aggregate computed from a value that did not
     * exist yet. Derived from the decisions rather than chosen per flow: that is the one
     * real discrepancy the characterization left, and this is where it stops being a
     * matter of which flow you are in.
     */
    public PipelinePhase refreshDerivedValuesAfter() {
        PipelinePhase last = PipelinePhase.CONSTRUCT_RECORDS;
        for (PipelinePhase phase : PipelinePhase.values()) {
            if (phase == PipelinePhase.REFRESH_DERIVED_VALUES) break;
            if (phase.producesFieldValues() && runs(phase)) last = phase;
        }
        return last;
    }

    /** The run, phase by phase, as a reader is shown it. */
    public String explain() {
        StringBuilder said = new StringBuilder();
        for (PipelinePhase phase : PipelinePhase.values()) {
            said.append(String.format("%-36s%s%n", phase.label(), decision(phase)));
        }
        return said.toString();
    }
}
