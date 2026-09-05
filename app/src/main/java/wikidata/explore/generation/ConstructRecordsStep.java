package wikidata.explore.generation;

import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.transform.StatementTransforms;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Reify statements into modeled records, and refresh what is derived from them.
 *
 * <p>Runs once per graph. Whether it runs at all is the checkpoint's answer, not the
 * flow's: a graph that already holds constructed records would gain a second copy of
 * every one of them.
 *
 * <p>Companion matching is the only part that may acquire. That choice is explicit in
 * the factory: {@link #acquiring(Function)} is REQUIRED network work, while
 * {@link #replaying(Map)} is wholly local. An arbitrary function can therefore never
 * hide behind OPTIONAL in a Remap.
 */
public final class ConstructRecordsStep implements PipelineStep {

    private final Function<List<WikidataDynamicObject>, Map<String, Set<List<String>>>>
            companionSetsFor;
    private final NetworkUse networkUse;

    private ConstructRecordsStep(
            Function<List<WikidataDynamicObject>, Map<String, Set<List<String>>>>
                    companionSetsFor, NetworkUse networkUse) {
        this.companionSetsFor = companionSetsFor;
        this.networkUse = networkUse;
    }

    /** Construction whose companion sets are acquired from an external source. */
    public static ConstructRecordsStep acquiring(
            Function<List<WikidataDynamicObject>, Map<String, Set<List<String>>>>
                    companionSetsFor) {
        if (companionSetsFor == null) {
            throw new IllegalArgumentException("No companion-set acquisition");
        }
        return new ConstructRecordsStep(companionSetsFor, NetworkUse.REQUIRED);
    }

    /** Purely local construction replaying companion sets retained by an earlier run. */
    public static ConstructRecordsStep replaying(
            Map<String, Set<List<String>>> companionSets) {
        Map<String, Set<List<String>>> retained = companionSets == null
                ? Map.of() : Map.copyOf(companionSets);
        return new ConstructRecordsStep(records -> retained, NetworkUse.NONE);
    }

    @Override public PipelinePhase phase() {
        return PipelinePhase.CONSTRUCT_RECORDS;
    }

    @Override public GraphCheckpoint.Stage requires() {
        return GraphCheckpoint.Stage.NORMALIZED_SOURCE_GRAPH;
    }

    @Override public GraphCheckpoint.Stage produces() {
        return GraphCheckpoint.Stage.CONSTRUCTED_GRAPH;
    }

    @Override public NetworkUse networkUse() {
        return networkUse;
    }

    @Override public String execute(PipelineContext context, PipelineState state) {
        StatementTransforms.Result result = StatementTransforms.apply(
                context.run().request().model(), context.run().model(), state.pool(),
                companionSetsFor, context.log());
        state.constructed(result);
        state.records().clear();
        state.records().addAll(result.reified());
        return result.reified().size() + " record(s) constructed";
    }
}
