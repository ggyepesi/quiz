package wikidata.explore.generation;

import dataset.DomainStorage;
import datasource.graph.GraphDiscoveryState;
import objectview.Viewable;
import quiz.DatasetRegistry;
import wikidata.explore.codegen.GeneratedViewableRuntime;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.LoadedDeclaration;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;
import wikidata.explore.model.ConstructInventory;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.transform.Canonicalization;
import wikidata.explore.transform.DescriptiveVocabularyBuild;
import wikidata.explore.transform.SelfReferenceLedger;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Loading a project's saved instances, as an operation that runs without a window
 * (directive 22). A build reuses a saved output this way instead of regenerating it.
 *
 * <p>It lived in the ModelBuilder frame's Load instances button, and the first half of it —
 * reading the snapshot onto the current construct inventory — was written a second time in
 * Save, for re-saving a snapshot nobody had loaded. {@link #project} is now that one
 * reading for both: a class renamed since the snapshot's Save is restamped, and one removed
 * since stops being claimed. {@link #load} then does what only loading does: the current
 * model's canonicalization and descriptive vocabularies, and materialization.
 */
public final class ProjectLoad {

    private ProjectLoad() { }

    /** The saved snapshot read onto the current inventory, with what it recorded beside its
     *  instances. */
    public record Projection(File file, List<WikidataDynamicObject> objects,
                             List<LoadedDeclaration> loadedDeclarations,
                             GraphDiscoveryState graphDiscovery,
                             SelfReferenceLedger selfReferences) { }

    /** A loaded run, the projection it came from, and a warning when the snapshot was
     *  generated from a different model than the one it is mapped through. */
    public record Loaded(GenerationRun run, Projection projection, String staleWarning) {
        public boolean stale() { return staleWarning != null && !staleWarning.isBlank(); }
    }

    /**
     * Reads {@code snapshot} as the current {@code model} names its classes. The file was
     * stamped against the inventory its Save committed, which {@code storage} keeps.
     */
    public static Projection project(GeneratedProjectModel model, DomainStorage storage,
                                     File snapshot) throws Exception {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(snapshot, "snapshot");
        WikidataDynamicObjectJsonStore.LoadedSnapshot saved =
                new WikidataDynamicObjectJsonStore().loadAllWithFieldGraph(snapshot);
        ConstructInventory inventory = ConstructInventory.of(model);
        Map<String, String> renames = inventory.renamesSince(storage.savedInventory(model.name()));
        GenerationRuns.renameClasses(saved.objects(), renames);
        return new Projection(snapshot, inventory.retractRemovedClaims(saved.objects()),
                GenerationRuns.renamedDeclarations(saved.loadedDeclarations(), renames),
                saved.graphDiscovery(), saved.selfReferences());
    }

    /**
     * Loads {@code snapshot} as a run of {@code model}: projected, canonicalized with the
     * current display-name rules, its descriptive vocabularies derived again (they are not
     * persisted), and materialized. Reaches no network. {@code log} receives each step's
     * report.
     */
    public static Loaded load(GeneratedProjectModel model, DomainStorage storage, File snapshot,
                              Consumer<String> log) throws Exception {
        Consumer<String> say = log == null ? ignored -> { } : log;
        String stale = staleWarning(model, storage, snapshot);
        Projection projection = project(model, storage, snapshot);
        List<WikidataDynamicObject> objects = projection.objects();

        GeneratedProjectModel run = model.copy();
        Canonicalization.apply(run, objects, GenerationLog.of(say));
        DescriptiveVocabularyBuild.apply(run, objects, GenerationLog.of(say));

        GenerationPipeline pipeline = new GenerationPipeline();
        GeneratedViewableRuntime runtime = pipeline.buildRuntime(run);
        List<Viewable> instances = pipeline.materialize(runtime, objects, say);
        return new Loaded(new GenerationRun(run, 0, pipeline.plan(run), objects, runtime,
                instances, null, projection.loadedDeclarations(),
                GenerationRun.Quality.completeQuality(), List.of(),
                GenerationRun.SelfReferenceAudit.restored(projection.selfReferences()),
                GenerationRun.OwnedCompositionAudit.notRun(),
                GenerationRun.KindClassificationAudit.notRun(),
                GenerationRun.ProjectionAudit.notRun()), projection, stale);
    }

    /** The registry records the model a served snapshot was generated from; a different
     *  current model may not match its fields. */
    private static String staleWarning(GeneratedProjectModel model, DomainStorage storage,
                                       File snapshot) {
        try {
            File want = snapshot.getCanonicalFile();
            for (DatasetRegistry.Dataset dataset
                    : DatasetRegistry.load(storage.registryFile()).datasets()) {
                if (dataset.snapshotPath().isBlank()
                        || !new File(dataset.snapshotPath()).getCanonicalFile().equals(want)) {
                    continue;
                }
                return DomainSave.signaturesDisagree(
                        dataset.modelSignature(), DomainSave.signature(model))
                        ? "The instances in " + snapshot.getPath() + " were generated from a "
                                + "different model version than the current model. Fields may "
                                + "not match; generate again to refresh them."
                        : null;
            }
        } catch (Exception unreadableRegistry) {
            return null;
        }
        return null;
    }
}
