package wikidata.explore.generation;

import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.GenerationLog;

/**
 * What every step is given: the compiled run, and the services it may use.
 *
 * <p>One context for every step, so a step cannot reach for something the run did not
 * grant it. In particular {@link #entityApi()} is absent when acquisition is forbidden,
 * which makes "Remap reaches no network" a property of what a step HAS rather than of
 * what it remembers not to call.
 */
public record PipelineContext(
        CompiledPipelineRun run,
        WikidataApiClient entityApi,
        GenerationLog log,
        work.CancellationToken cancellation) {

    public PipelineContext {
        if (run == null) throw new IllegalArgumentException("A context needs its run");
        log = log == null ? GenerationLog.NOOP : log;
        if (!run.request().mayAcquire() && entityApi != null) {
            throw new IllegalArgumentException(
                    "A run forbidden to acquire is not given a client to acquire with");
        }
    }

    public boolean cancelled() {
        return cancellation != null && cancellation.isCancelled();
    }
}
