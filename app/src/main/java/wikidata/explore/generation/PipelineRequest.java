package wikidata.explore.generation;

import wikidata.explore.model.GeneratedProjectModel;

/**
 * What a run was asked to do — the whole of it, before anything runs.
 *
 * <p>Generate domain, Generate class preview, Sample, Enrich and Remap are named
 * factories over this record. They are not five implementations and they are not a
 * five-valued mode switch: each is a combination of four decisions that already exist in
 * the code, said once instead of re-derived per flow.
 *
 * <table>
 *   <caption>The five flows as combinations</caption>
 *   <tr><th>flow</th><th>input</th><th>scope</th><th>acquisition</th></tr>
 *   <tr><td>Generate domain</td><td>empty</td><td>whole domain</td><td>ALL_REQUIRED</td></tr>
 *   <tr><td>Class preview</td><td>empty</td><td>a production chain</td><td>ALL_REQUIRED</td></tr>
 *   <tr><td>Sample</td><td>empty</td><td>a production chain</td><td>ALL_REQUIRED</td></tr>
 *   <tr><td>Enrich</td><td>final checkpoint</td><td>existing population</td><td>MISSING_ONLY</td></tr>
 *   <tr><td>Remap</td><td>a checkpoint</td><td>existing population</td><td>NONE</td></tr>
 * </table>
 *
 * <p>Preview and Sample differ from Generate domain by scope and limits, and by nothing
 * else. That is the design's invariant, and stating it in the request is what stops it
 * being violated by a flow quietly skipping a phase.
 *
 * <p>This record only says what was asked. Whether a phase can run under it, and what to
 * report when it cannot, is the planner's — Milestone 2.
 */
public record PipelineRequest(
        GeneratedProjectModel model,
        PipelineInput input,
        PipelineScope scope,
        Acquisition acquisition,
        PipelineLimits limits,
        Output output) {

    /**
     * Whether external facts may be requested.
     *
     * <p>A permission, not a promise: what is actually fetched follows from the compiled
     * demands and what the checkpoint already covers.
     */
    public enum Acquisition {
        /** Everything the model needs. */
        ALL_REQUIRED,
        /** Only what the input graph does not already answer. */
        MISSING_ONLY,
        /** None. No phase may reach the network. */
        NONE
    }

    /** Whether the result may replace what is open, and when. */
    public enum Output {
        /** Shown, and nothing else. */
        PREVIEW,
        /** Offered for an explicit Apply; it does not apply itself. */
        REPLACEMENT_CANDIDATE
    }

    public PipelineRequest {
        if (model == null) throw new IllegalArgumentException("A run needs a model");
        if (input == null) throw new IllegalArgumentException("A run needs an input");
        if (scope == null) throw new IllegalArgumentException("A run needs a scope");
        if (acquisition == null) {
            throw new IllegalArgumentException("A run needs an acquisition permission");
        }
        limits = limits == null ? PipelineLimits.asConfigured() : limits;
        output = output == null ? Output.PREVIEW : output;
        // An empty graph and no permission to fill it can produce nothing at all. Every
        // other combination is a run that does something; this one is a request that
        // cannot be answered, and refusing it here beats explaining it later.
        if (input.isEmpty() && acquisition == Acquisition.NONE) {
            throw new IllegalArgumentException(
                    "A run starting from nothing, forbidden to acquire, has no source");
        }
        if (input.isEmpty() && !scope.discovers()) {
            throw new IllegalArgumentException(
                    "A run starting from nothing has no existing population to scope to");
        }
    }

    public static PipelineRequest generateDomain(GeneratedProjectModel model) {
        return new PipelineRequest(model, PipelineInput.empty(), PipelineScope.wholeDomain(),
                Acquisition.ALL_REQUIRED, PipelineLimits.asConfigured(),
                Output.REPLACEMENT_CANDIDATE);
    }

    /** One class and what it takes to make it, bounded, shown and not applied. */
    public static PipelineRequest generateClassPreview(
            GeneratedProjectModel model, String className, int depth) {
        return new PipelineRequest(model, PipelineInput.empty(),
                PipelineScope.productionChainOf(className), Acquisition.ALL_REQUIRED,
                new PipelineLimits(PipelineLimits.AS_CONFIGURED, depth), Output.PREVIEW);
    }

    /** The same as a preview, bounded harder. The difference is the number. */
    public static PipelineRequest sampleClass(
            GeneratedProjectModel model, String className, int members) {
        return new PipelineRequest(model, PipelineInput.empty(),
                PipelineScope.productionChainOf(className), Acquisition.ALL_REQUIRED,
                PipelineLimits.members(members), Output.PREVIEW);
    }

    public static PipelineRequest enrich(
            GeneratedProjectModel model, GraphCheckpoint checkpoint) {
        return new PipelineRequest(model, PipelineInput.from(checkpoint),
                PipelineScope.existingPopulation(), Acquisition.MISSING_ONLY,
                PipelineLimits.asConfigured(), Output.REPLACEMENT_CANDIDATE);
    }

    /** Local reconstruction from the best checkpoint there is. Reaches no network. */
    public static PipelineRequest remap(
            GeneratedProjectModel model, GraphCheckpoint checkpoint) {
        return new PipelineRequest(model, PipelineInput.from(checkpoint),
                PipelineScope.existingPopulation(), Acquisition.NONE,
                PipelineLimits.asConfigured(),
                Output.REPLACEMENT_CANDIDATE);
    }

    /** Whether any phase of this run may reach the network. */
    public boolean mayAcquire() {
        return acquisition != Acquisition.NONE;
    }

    @Override public String toString() {
        return "from " + input + " over " + scope + ", acquiring " + acquisition
                + ", " + limits + ", as " + output;
    }
}
