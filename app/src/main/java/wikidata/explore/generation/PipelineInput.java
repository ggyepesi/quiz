package wikidata.explore.generation;

/**
 * The graph a pipeline run starts from.
 *
 * <p>Empty input and a typed checkpoint are different shapes, not an enum beside an
 * independently supplied checkpoint. Keeping the checkpoint here makes its stage the
 * one answer to what has already happened to the graph.
 */
public sealed interface PipelineInput
        permits PipelineInput.Empty, PipelineInput.Checkpoint {

    /** A run which must discover its population. */
    record Empty() implements PipelineInput {
        @Override public String toString() { return "an empty graph"; }
    }

    /** A run continuing from an existing graph at the checkpoint's declared stage. */
    record Checkpoint(GraphCheckpoint checkpoint) implements PipelineInput {
        public Checkpoint {
            if (checkpoint == null) {
                throw new IllegalArgumentException("Checkpoint input needs a checkpoint");
            }
        }

        @Override public String toString() {
            return checkpoint.stage() + " checkpoint";
        }
    }

    static PipelineInput empty() {
        return new Empty();
    }

    static PipelineInput from(GraphCheckpoint checkpoint) {
        return new Checkpoint(checkpoint);
    }

    default boolean isEmpty() {
        return this instanceof Empty;
    }

    default java.util.Optional<GraphCheckpoint> suppliedCheckpoint() {
        return this instanceof Checkpoint supplied
                ? java.util.Optional.of(supplied.checkpoint()) : java.util.Optional.empty();
    }
}
