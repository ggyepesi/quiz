package wikidata.explore.generation;

import objectview.Viewable;
import wikidata.explore.codegen.GeneratedViewableRuntime;
import wikidata.explore.extract.LoadedDeclaration;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.transform.PoolCopy;

import java.util.ArrayList;
import java.util.List;

/**
 * What a run has produced so far, and how far it has got.
 *
 * <p>Advanced by steps, in order. It is mutable because a run IS a sequence of
 * advances — a step reads what the last one left and adds to it — and pretending
 * otherwise would mean copying the whole graph between phases.
 *
 * <p>The {@link #stage()} is the part that makes the sequence checkable: a step declares
 * the stage it needs, and a state that has not reached it refuses rather than running
 * the step on a graph it cannot handle. That is the mistake a bare
 * {@code List<WikidataDynamicObject>} cannot report.
 */
public final class PipelineState {

    private GraphCheckpoint.Stage stage;
    private final List<WikidataDynamicObject> pool;
    private final List<WikidataDynamicObject> records = new ArrayList<>();
    private final List<LoadedDeclaration> loadedDeclarations = new ArrayList<>();
    private GeneratedViewableRuntime runtime;
    private List<Viewable> instances = List.of();
    private DomainFinalization.Result finalization;

    public PipelineState(
            GraphCheckpoint.Stage stage, List<WikidataDynamicObject> pool) {
        this(stage, pool, false);
    }

    private PipelineState(
            GraphCheckpoint.Stage stage, List<WikidataDynamicObject> pool, boolean share) {
        if (stage == null) throw new IllegalArgumentException("A state needs a stage");
        this.stage = stage;
        this.pool = pool == null ? new ArrayList<>()
                : share ? pool : new ArrayList<>(pool);
    }

    /**
     * A state over a graph a caller already holds — the same list, not a copy.
     *
     * <p>Finalization prunes: dead stubs, orphans, records missing a required field. A
     * copy would prune the copy and leave the caller's list holding what was removed, so
     * a flow being routed through the executor one step at a time would quietly stop
     * pruning. The state IS the run's graph; this says so rather than hiding an alias.
     */
    public static PipelineState over(
            GraphCheckpoint.Stage stage, List<WikidataDynamicObject> pool) {
        return new PipelineState(stage, pool, true);
    }

    /** The state a run starts in, from the graph it was given. */
    public static PipelineState from(GraphCheckpoint checkpoint) {
        if (checkpoint == null) throw new IllegalArgumentException("No checkpoint");
        // One graph-preserving copy for pool and record selection together. Copying the
        // two lists separately would make a record a different object from that same
        // record in the pool; copying only the list would still mutate checkpoint
        // objects in place.
        List<WikidataDynamicObject> combined = new ArrayList<>(checkpoint.objects());
        combined.addAll(checkpoint.records());
        List<WikidataDynamicObject> copied = PoolCopy.deepCopy(combined);
        int poolSize = checkpoint.objects().size();
        PipelineState state = new PipelineState(checkpoint.stage(),
                new ArrayList<>(copied.subList(0, poolSize)), true);
        state.records.addAll(copied.subList(poolSize, copied.size()));
        state.loadedDeclarations.addAll(checkpoint.loadedDeclarations());
        return state;
    }

    /** A run that starts from nothing: no objects, and no stage reached. */
    public static PipelineState empty() {
        return new PipelineState(GraphCheckpoint.Stage.NORMALIZED_SOURCE_GRAPH, List.of());
    }

    public GraphCheckpoint.Stage stage() {
        return stage;
    }

    /** The graph itself — the live list steps add to and prune. */
    public List<WikidataDynamicObject> pool() {
        return pool;
    }

    /** The statement records construction made, which finalization checks against. */
    public List<WikidataDynamicObject> records() {
        return records;
    }

    public List<LoadedDeclaration> loadedDeclarations() {
        return loadedDeclarations;
    }

    public GeneratedViewableRuntime runtime() {
        return runtime;
    }

    public List<Viewable> instances() {
        return instances;
    }

    public DomainFinalization.Result finalization() {
        return finalization;
    }

    /** Only forwards: a stage is something a graph has reached, not a setting. */
    public void reached(GraphCheckpoint.Stage reached) {
        if (reached == null) throw new IllegalArgumentException("No stage to reach");
        if (reached.ordinal() < stage.ordinal()) {
            throw new IllegalStateException(
                    "A graph does not go back from " + stage + " to " + reached);
        }
        stage = reached;
    }

    public void materialized(GeneratedViewableRuntime runtime, List<Viewable> instances) {
        this.runtime = runtime;
        this.instances = instances == null ? List.of() : List.copyOf(instances);
    }

    public void finalized(DomainFinalization.Result result) {
        finalization = result;
    }
}
