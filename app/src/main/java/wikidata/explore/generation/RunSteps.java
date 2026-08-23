package wikidata.explore.generation;

import process.ProcessWorkflowPipeline;

/**
 * The steps a run reports as it goes, without the run having to hold a pipeline.
 *
 * <p>Generate names its phases as it works; Remap and Enrich ran as one opaque step, so
 * their plan said "Remap locally" and their result had nowhere to hang what each part of
 * the work accounted for. They do the same steps — construct, settle semantics, finalize
 * — and now say so.
 *
 * <p>A seam rather than the pipeline itself because {@link GenerationPipeline} is also
 * driven headlessly and from tests, where there is no pipeline to report to and silence
 * is the right behaviour rather than a null check at every call.
 */
public interface RunSteps {

    /** Nothing is listening — the default for every caller that does not report. */
    RunSteps SILENT = new RunSteps() {
        @Override public void started(String id, String summary) { }
        @Override public void completed(String id, String summary) { }
    };

    /** A step is about to do its work. This is what drives live highlighting and starts
     * the elapsed-time clock before, rather than after, the expensive block. */
    void started(String phaseId, String summary);

    /**
     * One step finished, with what it produced.
     *
     * <p>Finishing is the only event worth reporting from here: a step's identity and
     * order are configuration the pipeline already holds, and what it produced is the
     * one thing only the step itself knows.
     */
    void completed(String phaseId, String summary);

    /** Reports into a pipeline, skipping phases it does not declare — an operation whose
     *  plan has fewer steps simply hears less, rather than growing steps it never ran. */
    static RunSteps of(ProcessWorkflowPipeline pipeline) {
        if (pipeline == null) {
            return SILENT;
        }
        return new RunSteps() {
            private boolean declared(String phaseId) {
                return pipeline.snapshot().stream()
                        .anyMatch(state -> state.phase().id().equals(phaseId));
            }

            @Override public void started(String phaseId, String summary) {
                if (declared(phaseId)) pipeline.start(phaseId, summary);
            }

            @Override public void completed(String phaseId, String summary) {
                if (declared(phaseId)) pipeline.complete(phaseId, summary);
            }
        };
    }
}
