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
 * <p>{@link PipelineStep.NetworkUse#OPTIONAL}, because only one part of it can acquire.
 * Companion matching needs sets a request produces, and everything else — reify,
 * restrictions, inverts, projections — is local. A run that cannot acquire supplies the
 * sets it cached instead, which is exactly what makes a Remap from a normalized graph a
 * full reconstruction rather than a partial one.
 */
public final class ConstructRecordsStep implements PipelineStep {

    private final Function<List<WikidataDynamicObject>, Map<String, Set<List<String>>>>
            companionSetsFor;

    /**
     * @param companionSetsFor how the companion-match sets are obtained: fetched for a
     *                         run that may acquire, replayed from cache for one that may
     *                         not. The difference between fetching and replaying lives
     *                         here because it is already a parameter of the construct
     *                         itself — this step does not decide it a second time.
     */
    public ConstructRecordsStep(
            Function<List<WikidataDynamicObject>, Map<String, Set<List<String>>>>
                    companionSetsFor) {
        this.companionSetsFor = companionSetsFor == null ? records -> Map.of()
                : companionSetsFor;
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
        return NetworkUse.OPTIONAL;
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
