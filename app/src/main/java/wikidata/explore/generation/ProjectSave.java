package wikidata.explore.generation;

import dataset.DomainStorage;
import datasource.graph.GraphDiscoveryState;
import quiz.DatasetRegistry;
import wikidata.explore.extract.LoadedDeclaration;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;
import wikidata.explore.model.ConstructInventory;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;
import wikidata.explore.rule.RuleTreeCompiler;
import wikidata.explore.rule.RuleTreeSerializer;
import wikidata.explore.transform.SelfReferenceLedger;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Saving a project, as an operation that runs without a UI (directive 22, the design's
 * SAVE_PROJECT_RESULT).
 *
 * <p>It lived inside the ModelBuilder frame's Save button, between the dialogs it fed:
 * which instances go to disk, which files are written, which are removed, and what each
 * write reports. None of it could run without the window. Now {@link #plan} answers every
 * question before anything is written — the exact files, the files removed, and the
 * warnings a caller must accept — and {@link #write} performs exactly that plan. A
 * desktop Save shows the plan and asks about the warnings; a headless build decides by
 * policy. Both write the same files the same way.
 *
 * <p>The construct manifest is written last, so it never claims a save that did not
 * finish.
 */
public final class ProjectSave {

    /**
     * Instances generated or loaded in this session, as held: the whole pool, the
     * fetched-declaration records, the self-reference ledger, and the model they were
     * stamped against. Absent when there are none, in which case the saved snapshot, if
     * any, is projected onto the current inventory and written again.
     */
    public record Run(List<WikidataDynamicObject> pool, List<LoadedDeclaration> loadedDeclarations,
                      SelfReferenceLedger selfReferences, GeneratedProjectModel producedBy,
                      String generatedFrom) {
        public Run {
            pool = pool == null ? List.of() : List.copyOf(pool);
            loadedDeclarations = loadedDeclarations == null ? List.of() : loadedDeclarations;
            selfReferences = selfReferences == null ? SelfReferenceLedger.EMPTY : selfReferences;
            generatedFrom = generatedFrom == null ? "" : generatedFrom;
        }

        /** A run freshly generated from {@code producedBy}. */
        public Run(List<WikidataDynamicObject> pool, List<LoadedDeclaration> loadedDeclarations,
                   SelfReferenceLedger selfReferences, GeneratedProjectModel producedBy) {
            this(pool, loadedDeclarations, selfReferences, producedBy,
                    producedBy == null ? "" : DomainSave.signature(producedBy));
        }

        /** The signature of the model the instances were generated from; blank when not
         *  known. {@code producedBy} is the model they are stamped against, which after a
         *  Load or an Enrich is not the same thing (#315). */
        String modelSignature() {
            return generatedFrom;
        }
    }

    /** What to save: the model, this session's run (nullable), the graph ledger, and every
     *  graph annotation set the project holds. */
    public record Input(GeneratedProjectModel model, Run run, GraphDiscoveryState graphDiscovery,
                        List<GraphDiscoveryResultStore.Artifact> annotationSets) {
        public Input {
            Objects.requireNonNull(model, "A save needs the model it saves");
            graphDiscovery = graphDiscovery == null ? GraphDiscoveryState.EMPTY : graphDiscovery;
            annotationSets = annotationSets == null ? List.of() : List.copyOf(annotationSets);
        }
    }

    /** Something the caller must accept before the plan is written. */
    public record Warning(Kind kind, String message) {
        public enum Kind { STALE_INSTANCES, TYPES_DROPPED }
    }

    /** What a completed write did, one line per file, and how many members it saved. */
    public record Result(List<String> report, int instancesWritten) { }

    private final Input input;
    private final DomainStorage storage;
    private final ConstructInventory inventory;
    private final List<WikidataDynamicObject> roots;
    private final List<LoadedDeclaration> loadedDeclarations;
    private final GraphDiscoveryState graphDiscovery;
    private final SelfReferenceLedger selfReferences;
    private final List<Warning> warnings;
    private final List<File> removals;

    private ProjectSave(Input input, DomainStorage storage, List<WikidataDynamicObject> roots,
                        List<LoadedDeclaration> loadedDeclarations,
                        GraphDiscoveryState graphDiscovery, SelfReferenceLedger selfReferences,
                        List<Warning> warnings) {
        this.input = input;
        this.storage = storage;
        this.inventory = ConstructInventory.of(input.model());
        this.roots = List.copyOf(roots);
        this.loadedDeclarations = loadedDeclarations;
        this.graphDiscovery = graphDiscovery;
        this.selfReferences = selfReferences;
        this.warnings = List.copyOf(warnings);
        this.removals = storage.obsoleteConstructSnapshots(name(), inventory);
    }

    /**
     * Everything this save would do, decided before anything is written. Reads the saved
     * snapshot when there is no run (to write it again under the current inventory) and
     * when a run would replace it (to say which classes the run would drop).
     */
    public static ProjectSave plan(Input input, DomainStorage storage) throws Exception {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(storage, "storage");
        GeneratedProjectModel model = input.model();
        ConstructInventory inventory = ConstructInventory.of(model);
        File snapshot = storage.snapshotFile(model.name());
        Run run = input.run();
        if (run == null && !snapshot.isFile()) {
            return new ProjectSave(input, storage, List.of(), List.of(),
                    input.graphDiscovery(), SelfReferenceLedger.EMPTY, List.of());
        }
        if (run == null) {
            // Read the way Load reads it, so a class renamed since that Save keeps its
            // members and one removed since stops being claimed.
            ProjectLoad.Projection saved = ProjectLoad.project(model, storage, snapshot);
            return new ProjectSave(input, storage, inventory.memberRoots(saved.objects()),
                    saved.loadedDeclarations(), saved.graphDiscovery(), saved.selfReferences(),
                    List.of());
        }
        // A class renamed since the run is the same class under its new name; one removed
        // since stops being claimed by the objects it produced.
        Map<String, String> renames = run.producedBy() == null ? Map.of()
                : inventory.renamesSince(ConstructInventory.of(run.producedBy()));
        GenerationRuns.renameClasses(run.pool(), renames);
        List<WikidataDynamicObject> roots =
                inventory.memberRoots(inventory.retractRemovedClaims(run.pool()));
        List<Warning> warnings = new ArrayList<>();
        if (!roots.isEmpty() && DomainSave.instancesWouldBeStale(run.modelSignature(), model)) {
            warnings.add(new Warning(Warning.Kind.STALE_INSTANCES,
                    "The model has changed since these instances were generated, so the "
                            + "saved snapshot " + snapshot.getPath()
                            + " will not match the saved model."));
        }
        if (!roots.isEmpty() && snapshot.isFile()) {
            List<WikidataDynamicObject> onDisk;
            try {
                onDisk = new WikidataDynamicObjectJsonStore().load(snapshot);
            } catch (Exception unreadable) {
                onDisk = List.of();
            }
            List<String> dropped = DomainSave.typesDropped(run.pool(), onDisk);
            if (!dropped.isEmpty()) {
                warnings.add(new Warning(Warning.Kind.TYPES_DROPPED,
                        "This run produced only " + String.join(", ",
                                DomainSave.stampedTypes(run.pool()))
                                + ". Saving overwrites " + snapshot.getPath()
                                + " and drops these existing types: "
                                + String.join(", ", dropped) + "."));
            }
        }
        return new ProjectSave(input, storage, roots,
                GenerationRuns.renamedDeclarations(run.loadedDeclarations(), renames),
                input.graphDiscovery(), run.selfReferences(), warnings);
    }

    public List<Warning> warnings() { return warnings; }

    /** How many members the snapshot will hold; zero means no snapshot is written. */
    public int instances() { return roots.size(); }

    /**
     * Every file this save writes or removes, one line each — the same paths
     * {@link #write} uses, so a dialog can never promise one file while the save produces
     * another.
     */
    public List<String> planLines() {
        List<String> lines = new ArrayList<>();
        lines.add("Config:    " + storage.modelFile(name()).getPath());
        lines.add("Rule tree: " + storage.ruleTreeFile(name()).getPath());
        if (roots.isEmpty()) {
            lines.add("Instances: (none generated yet — will be skipped)");
        } else {
            lines.add("Instances: " + roots.size() + " -> " + storage.snapshotFile(name()).getPath());
            lines.add("Registry:  " + storage.registryFile().getPath()
                    + (servesDataset() ? "" : " (model working data, not served)"));
            lines.add("Counts:    " + storage.countsFile(name()).getPath());
        }
        for (GraphDiscoveryResultStore.Artifact set : input.annotationSets()) {
            lines.add("Graph annotations \"" + set.type() + "\": " + set.instances().size()
                    + " -> " + GraphDiscoveryResultStore.destination(
                            storage, set.projectName(), set.type()).getPath());
        }
        lines.add("Construct inventory: " + storage.constructManifestFile(name()).getPath());
        for (File obsolete : removals) lines.add("Remove obsolete snapshot: " + obsolete.getPath());
        return List.copyOf(lines);
    }

    /** Writes the plan: model, rule tree, instances (and for a domain its registry row),
     *  counts, graph annotations, then the construct manifest. */
    public Result write() throws Exception {
        GeneratedProjectModel model = input.model();
        List<String> report = new ArrayList<>();
        File modelFile = storage.modelFile(name());
        if (modelFile.getParentFile() != null) modelFile.getParentFile().mkdirs();
        new GeneratedProjectModelStore().save(DomainSave.persistedModel(model), modelFile);
        report.add("Config:    " + modelFile.getPath());

        File ruleTree = storage.ruleTreeFile(name());
        new RuleTreeSerializer().save(RuleTreeCompiler.compileProject(model), ruleTree);
        report.add("Rule tree: " + ruleTree.getPath());

        if (roots.isEmpty()) {
            report.add("Instances: (none generated yet — run \"Generate class instances\" "
                    + "first; not registered until model + snapshot are saved together)");
        } else {
            File snapshot = storage.snapshotFile(name());
            WikidataDynamicObjectJsonStore instanceStore = new WikidataDynamicObjectJsonStore();
            instanceStore.saveWithFieldGraph(roots, snapshot, model, loadedDeclarations,
                    graphDiscovery, selfReferences);
            report.add("Instances: " + roots.size() + " -> " + snapshot.getPath());
            // Domains are served. A model's snapshot is local working data for curation
            // and graph inputs: it is registered, so TransformApp can load it and Load
            // instances can check it, but not served — the one registry state TransformApp's
            // DomainSaver writes for a model too. Importing the model never imports it.
            report.add(register());
            report.add(appendCounts(instanceStore.persistedMembers()));
        }


        for (GraphDiscoveryResultStore.Artifact set : input.annotationSets()) {
            report.add(GraphDiscoveryResultStore.save(set, storage));
        }

        for (File removed : storage.reconcileConstructs(name(), inventory, snapshotSignature())) {
            report.add("Removed obsolete snapshot: " + removed.getPath());
        }
        report.add("Construct inventory: " + storage.constructManifestFile(name()).getPath());
        return new Result(List.copyOf(report), roots.size());
    }

    private String name() { return input.model().name(); }

    /** Which model produced the snapshot this save leaves on disk: the run's, when it
     *  writes one from a run; the previous record, when it re-saves the saved snapshot;
     *  none, when it writes no snapshot. */
    private String snapshotSignature() {
        if (roots.isEmpty()) return "";
        if (input.run() == null) return storage.savedSnapshotSignature(name());
        return input.run().modelSignature();
    }

    private boolean servesDataset() { return !input.model().isModel(); }

    private String register() {
        File file = storage.registryFile();
        try {
            GeneratedProjectModel model = input.model();
            DatasetRegistry registry = DatasetRegistry.load(file);
            DatasetRegistry.Dataset dataset = new DatasetRegistry.Dataset();
            dataset.name(model.name());
            dataset.key(DomainStorage.key(model.name()));
            dataset.rootClass(model.rootClass() == null ? "" : model.rootClass().className());
            dataset.modelPath(storage.modelFile(name()).getPath());
            dataset.ruletreePath(storage.ruleTreeFile(name()).getPath());
            dataset.snapshotPath(storage.snapshotFile(name()).getPath());
            dataset.modelSignature(input.run() == null ? "" : input.run().modelSignature());
            dataset.savedAt(java.time.LocalDateTime.now().toString());
            List<String> types = new ArrayList<>();
            for (GeneratedClassModel c : model.classes()) {
                if (c != null && c.className() != null) types.add(c.className());
            }
            dataset.types(types);
            dataset.served(servesDataset());
            registry.upsert(dataset);
            registry.save(file);
            return "Registry:  " + file.getPath()
                    + (servesDataset() ? "" : " (model working data, not served)");
        } catch (Exception error) {
            return "Registry:  could not update " + file.getPath() + ": " + error.getMessage();
        }
    }

    private String appendCounts(List<WikidataDynamicObjectJsonStore.PersistedMember> persisted) {
        File file = storage.countsFile(name());
        String row = java.time.LocalDateTime.now().withNano(0)
                + "\t" + DomainCounts.row(persisted) + "\n";
        try {
            boolean fresh = !file.isFile();
            boolean noted = !fresh
                    && Files.readString(file.toPath()).contains(DomainCounts.FORMAT_NOTE);
            try (FileWriter writer = new FileWriter(file, true)) {
                if (fresh) writer.write("# " + name() + " — per-class counts, one row per save\n");
                if (!noted) writer.write(DomainCounts.FORMAT_NOTE + "\n");
                writer.write(row);
            }
            return "Counts:    " + file.getPath();
        } catch (Exception error) {
            return "Counts:    could not write " + file.getPath() + ": " + error.getMessage();
        }
    }
}
