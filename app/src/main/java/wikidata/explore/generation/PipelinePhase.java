package wikidata.explore.generation;

/**
 * The phases every run is explained with, in the order they are displayed.
 *
 * <p>One vocabulary, so five flows stop describing similar work in different words. The
 * order here is for reading; whether a phase runs, and what follows what, is the
 * planner's — see {@link CompiledPipelineRun}.
 *
 * <p>{@link #CONSTRUCT_RECORDS} and {@link #REFRESH_DERIVED_VALUES} are separate
 * because they are separate operations that one name hid. Constructing records reifies
 * statements into modeled records and can only happen once; refreshing derived values
 * re-runs aggregates, restrictions, inverts and projections over whatever the pool now
 * holds, and is replayable. Reading both as "construct" made Generate and Enrich look
 * like they disagreed about ordering when they were doing different things.
 */
public enum PipelinePhase {
    COMPILE("Compile", false, false),
    STAGE_INPUT_GRAPH("Stage input graph", false, false),
    DISCOVER_POPULATION("Discover population", true, false),
    ACQUIRE_SOURCE_FACTS("Acquire source facts", true, true),
    CONSTRUCT_RECORDS("Construct modeled records", false, false),
    RESOLVE_SEMANTIC_WORKLIST("Resolve semantic worklist", true, true),
    ACQUIRE_EXTERNAL_EVIDENCE("Acquire remaining external evidence", true, true),
    REFRESH_DERIVED_VALUES("Refresh derived values", false, false),
    HYDRATE_NAMES("Hydrate names", true, false),
    FINALIZE("Finalize and validate", false, false),
    MATERIALIZE("Materialize instances", false, false);

    private final String label;
    private final boolean network;
    private final boolean producesFieldValues;

    PipelinePhase(String label, boolean network, boolean producesFieldValues) {
        this.label = label;
        this.network = network;
        this.producesFieldValues = producesFieldValues;
    }

    public String label() {
        return label;
    }

    /** Whether this phase can reach the network, and so is impossible without permission. */
    public boolean network() {
        return network;
    }

    /**
     * Whether this phase can supply a value that a derived value is computed from.
     *
     * <p>What schedules {@link #REFRESH_DERIVED_VALUES}: it belongs after the last of
     * these that actually runs, because an aggregate keyed on a field acquired later is
     * an aggregate computed from a value that did not exist yet. Generate refreshes
     * before its semantic and external phases and never again; Enrich refreshes after
     * both. That is not a Generate-versus-Enrich preference to encode — it is a
     * dependency, and the answer follows from which producers ran.
     */
    public boolean producesFieldValues() {
        return producesFieldValues;
    }

    @Override public String toString() {
        return label;
    }
}
