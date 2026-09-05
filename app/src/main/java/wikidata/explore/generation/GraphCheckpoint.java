package wikidata.explore.generation;

import datasource.graph.GraphDiscoveryState;
import wikidata.explore.extract.LoadedDeclaration;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

/**
 * A graph, and what has already happened to it.
 *
 * <p>The obstacle to running one pipeline over five flows is that a
 * {@code List<WikidataDynamicObject>} does not say what it contains. Reifying an
 * already-reified graph makes a second copy of every record; treating a final snapshot
 * as raw source output re-runs construction over records that are already constructed.
 * Neither mistake is visible in the list. The stage is what makes it visible.
 *
 * <p>What the checkpoint carries beyond the objects is what a later phase would
 * otherwise have to guess: which declarations have been fetched (so acquisition asks
 * only for what is new), what the graph-discovery ledger covers, and the fingerprint of
 * the model that produced it (so a checkpoint is not silently reused under an edited
 * model).
 */
public record GraphCheckpoint(
        Stage stage,
        List<WikidataDynamicObject> objects,
        List<LoadedDeclaration> loadedDeclarations,
        GraphDiscoveryState graphDiscovery,
        String modelSignature) {

    /**
     * How far a graph has been taken.
     *
     * <p>Semantic state, not storage location — a checkpoint at any stage may be in
     * memory or restored from a file.
     */
    public enum Stage {
        /** Acquired and normalized; modeled records not yet constructed. */
        NORMALIZED_SOURCE_GRAPH,
        /** Records constructed; the semantic worklist has not settled them. */
        CONSTRUCTED_GRAPH,
        /** Settled, finalized — what a snapshot holds. */
        FINAL_GRAPH
    }

    /**
     * What a local reconstruction can honestly do from this graph.
     *
     * <p>Remap re-runs the pure transforms offline. From a normalized graph it can run
     * all of them, because nothing has been constructed yet. From a final graph it can
     * only run those that are safe to apply to their own output — reify would build a
     * second copy of every record — and the difference has to be stated rather than
     * discovered by a reader wondering why a Remap did less than it did last time.
     */
    public enum RemapCapability {
        FULL_RECONSTRUCTION,
        IDEMPOTENT_ONLY;

        @Override public String toString() {
            return this == FULL_RECONSTRUCTION
                    ? "full reconstruction" : "idempotent transforms only";
        }
    }

    public GraphCheckpoint {
        if (stage == null) throw new IllegalArgumentException("A checkpoint needs a stage");
        objects = objects == null ? List.of() : List.copyOf(objects);
        loadedDeclarations = loadedDeclarations == null
                ? List.of() : List.copyOf(loadedDeclarations);
        graphDiscovery = graphDiscovery == null ? GraphDiscoveryState.EMPTY : graphDiscovery;
        modelSignature = modelSignature == null ? "" : modelSignature.trim();
    }

    /** A graph as acquired, before anything was constructed from it. */
    public static GraphCheckpoint normalized(List<WikidataDynamicObject> objects,
            List<LoadedDeclaration> loaded, GraphDiscoveryState discovery,
            String modelSignature) {
        return new GraphCheckpoint(Stage.NORMALIZED_SOURCE_GRAPH, objects, loaded,
                discovery, modelSignature);
    }

    /** A settled graph — a saved snapshot, or the end of a run. */
    public static GraphCheckpoint finalGraph(List<WikidataDynamicObject> objects,
            List<LoadedDeclaration> loaded, GraphDiscoveryState discovery,
            String modelSignature) {
        return new GraphCheckpoint(Stage.FINAL_GRAPH, objects, loaded, discovery,
                modelSignature);
    }

    /**
     * What a Remap from this checkpoint can do.
     *
     * <p>Derived, never stored: it IS the stage, said in the words of the flow that
     * cares. Storing it as well would be a second answer to keep in step.
     */
    public RemapCapability remapCapability() {
        return stage == Stage.NORMALIZED_SOURCE_GRAPH
                ? RemapCapability.FULL_RECONSTRUCTION : RemapCapability.IDEMPOTENT_ONLY;
    }

    /** Which request input this checkpoint answers. */
    public PipelineRequest.Input asInput() {
        return switch (stage) {
            case NORMALIZED_SOURCE_GRAPH -> PipelineRequest.Input.NORMALIZED_CHECKPOINT;
            case CONSTRUCTED_GRAPH -> PipelineRequest.Input.CONSTRUCTED_CHECKPOINT;
            case FINAL_GRAPH -> PipelineRequest.Input.SAVED_GRAPH;
        };
    }

    /**
     * Whether this checkpoint was produced by the model now in hand.
     *
     * <p>An unknown signature on either side makes no claim — an uncompilable model has
     * none, and a checkpoint from before signatures were recorded has none either. The
     * caller decides what to do about not knowing; this does not answer "yes" for it.
     */
    public boolean producedBy(String currentSignature) {
        return !modelSignature.isEmpty() && currentSignature != null
                && modelSignature.equals(currentSignature.trim());
    }

    public boolean isEmpty() {
        return objects.isEmpty();
    }
}
