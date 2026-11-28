package domain.transform.app;

import domain.DomainCapability;
import domain.DelegatingDomainModel;
import domain.build.GenerationRun;
import domain.curation.CorrectionsSidecar;
import domain.build.ProjectLoad;
import domain.compile.transform.BareReferenceCollapse;
import domain.compile.transform.PoolCopy;
import domain.compile.transform.ProductCompiler;
import domain.runtime.SnapshotDomain;

import domain.runtime.WikidataDynamicObject;

import domain.persistence.DatasetRegistry;
import domain.persistence.ProjectSaveGuard;
import domain.config.GeneratedProjectModel;
import domain.config.GeneratedProjectModelStore;
import domain.config.GeneratedClassModel;
import domain.curation.CuratedInstances;
import domain.curation.ManualCuration;
import dataset.DomainStorage;
import domain.legacy.LegacyDomains;
import domain.transform.ui.DomainEntry;
import domain.transform.ui.ProjectBacking;
import domain.transform.ui.ReflectionDomain;
import domain.runtime.WikidataDynamicObjectJsonStore.LoadedSnapshot;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import objectview.field.FieldSchema;
import domain.DomainModel;

/**
 * Assembles the {@link DomainEntry} catalog for the navigator from the saved Wikidata
 * datasets ({@link DatasetRegistry}). TransformApp additionally receives the hand-written
 * domains (States, Oscars, …) as live conversion inputs. Product consumers call
 * {@link #configured()} and see saved served snapshots only.
 */
public final class DomainCatalog {

    private DomainCatalog() {}

    /** Saved product domains available to QuizFactory and the web-facing tools.
     * Live hand-written domains deliberately do not enter this list: TransformApp
     * exposes those as conversion sources, while a quiz consumes the saved result. */
    public static List<DomainEntry> configured() {
        return registered(DatasetRegistry.load(), true);
    }

    public static List<DomainEntry> all() {
        List<DomainEntry> out = new ArrayList<>(savedProjects(DomainStorage.inDefaultLocation(), false));

        // Live hand-written domains remain available here as conversion inputs. They
        // are not QuizFactory products until TransformApp saves their configured snapshot.
        for (LegacyDomains.Entry b : LegacyDomains.entries()) {
            out.add(new DomainEntry(b.icon() + " " + b.name(), "built-in",
                    "Load built-in domain \"" + b.name() + "\" from the running application.",
                    () -> ReflectionDomain.of(b.views())));
        }

        return out;
    }

    /** Saved model-backed projects with an instance snapshot. */
    public static List<DomainEntry> modelBacked() {
        return modelBacked(DomainStorage.inDefaultLocation());
    }

    public static List<DomainEntry> modelBacked(DomainStorage storage) {
        return savedProjects(storage, true);
    }

    /** The same project catalog, with the saved run available to an execution workbench. */
    public static List<DomainEntry> runBacked() {
        return runBacked(DomainStorage.inDefaultLocation());
    }

    public static List<DomainEntry> runBacked(DomainStorage storage) {
        return savedProjects(storage, true, (snapshot, model, location) -> {
            var opened = openRun(snapshot, model, location, ignored -> { });
            var run = new SavedRun(opened.loaded());
            return new DelegatingDomainModel(opened.domain()) {
                @Override public <T extends DomainCapability> T capability(Class<T> type) {
                    return type == SavedRun.class ? type.cast(run) : super.capability(type);
                }
            };
        });
    }

    public record SavedRun(ProjectLoad.Loaded loaded) implements DomainCapability { }

    @FunctionalInterface
    private interface ProjectOpener {
        DomainModel open(File snapshot, File model, DomainStorage storage) throws Exception;
    }

    private static List<DomainEntry> savedProjects(DomainStorage storage, boolean modelBackedOnly) {
        return savedProjects(storage, modelBackedOnly, DomainCatalog::open);
    }

    private static List<DomainEntry> savedProjects(DomainStorage storage, boolean modelBackedOnly,
            ProjectOpener opener) {
        List<DomainEntry> out = new ArrayList<>(
                registered(DatasetRegistry.load(storage.registryFile()), false, modelBackedOnly, storage, opener));
        java.util.Set<String> registeredNames = out.stream().map(DomainEntry::name)
                .collect(java.util.stream.Collectors.toSet());
        out.addAll(unregisteredProjects(storage, registeredNames,
                name -> DomainSaver.destination(storage, name), opener));
        return out;
    }

    /** Registry-backed entries. Package-visible overload lets the catalogue rule be
     * forced without replacing the user's real datasets.json. */
    static List<DomainEntry> registered(
            DatasetRegistry registry, boolean servedOnly) {
        return registered(registry, servedOnly, false, DomainStorage.inDefaultLocation(), DomainCatalog::open);
    }

    private static List<DomainEntry> registered(
            DatasetRegistry registry, boolean servedOnly, boolean modelBackedOnly,
            DomainStorage storage, ProjectOpener opener) {
        List<DomainEntry> out = new ArrayList<>();
        List<DatasetRegistry.Dataset> saved = registry == null
                ? List.of() : registry.datasets().stream()
                        .sorted(java.util.Comparator.comparing(
                                DatasetRegistry.Dataset::isModelBacked).reversed())
                        .toList();
        java.util.Set<DatasetRegistry.Dataset> consumed =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        for (DatasetRegistry.Dataset d : saved) {
            if (consumed.contains(d)) continue;
            if (modelBackedOnly && !d.isModelBacked()) continue;
            if (servedOnly && !d.served()) {
                if (d.isModelBacked()) {
                    saved.stream()
                            .filter(candidate -> candidate != d
                                    && !candidate.isModelBacked())
                            .filter(candidate -> java.util.Objects.equals(
                                    d.name(), candidate.name()))
                            .forEach(consumed::add);
                }
                consumed.add(d);
                continue;
            }
            DatasetRegistry.Dataset snapshotOwner = d;
            DatasetRegistry.Dataset modelOwner = d.isModelBacked() ? d : null;
            if (modelOwner != null) {
                // Repair the old Save-as-domain split at read time: one same-named,
                // detached transform snapshot is the latest working result of this
                // project, not a second project. Saving it with the corrected writer
                // consolidates the registry permanently.
                DatasetRegistry.Dataset transformed = saved.stream()
                        .filter(candidate -> candidate != d && !candidate.isModelBacked())
                        .filter(candidate -> java.util.Objects.equals(
                                d.name(), candidate.name()))
                        .max(java.util.Comparator.comparing(
                                DatasetRegistry.Dataset::savedAt)).orElse(null);
                if (transformed != null) {
                    snapshotOwner = transformed;
                    consumed.add(transformed);
                }
            }
            File snap = new File(snapshotOwner.snapshotPath() == null
                    ? "" : snapshotOwner.snapshotPath());
            if (snap.isFile()) {
                File model = new File(modelOwner == null ? "" : modelOwner.modelPath());
                if (modelBackedOnly && !model.isFile()) continue;
                File selectedSnapshot = snap;
                File selectedModel = model;
                out.add(new DomainEntry(d.name(), "generated",
                        "Load domain \"" + d.name() + "\", its instances and saved model from "
                                + snap.getPath() + (model.isFile()
                                ? " and " + model.getPath() : "") + ".",
                        () -> opener.open(selectedSnapshot, selectedModel, storage)));
            }
            consumed.add(d);
        }
        return out;
    }

    /** Opens an editable project and requires its saved model alongside the snapshot. */
    public static DomainModel openProject(File snapshot, File model, DomainStorage storage)
            throws Exception {
        if (!model.isFile()) throw new IOException("No saved model at " + model.getPath());
        DomainModel loaded = open(snapshot, model, storage);
        if (loaded.capability(ProjectBacking.class) == null) {
            throw new IOException("No saved model at " + model.getPath());
        }
        return loaded;
    }

    /**
     * Opens a generated dataset: when model.json exists, compile it with the pool
     * into a typed {@link ProductDomain} (model-authoritative schema); a dataset
     * without a model uses the snapshot's persisted field graph.
     */
    public static DomainModel open(File snap, File model, DomainStorage storage)
            throws Exception {
        return openSaved(snap, model, storage, null).domain();
    }

    /** Read both presentations of one snapshot: the raw execution run and the curated
     * working domain. Curation never owns the graph later saved or enriched by the run. */
    public record OpenedRun(DomainModel domain, ProjectLoad.Loaded loaded) { }

    public static OpenedRun openRun(File snapshot, File model, DomainStorage storage,
            java.util.function.Consumer<String> log) throws Exception {
        Objects.requireNonNull(log);
        return openSaved(snapshot, model, storage, log);
    }

    private static OpenedRun openSaved(File snap, File model, DomainStorage storage,
            java.util.function.Consumer<String> runLog) throws Exception {
        ProjectSaveGuard guard = null;
        GeneratedProjectModel project = null;
        if (model != null && model.isFile()) {
            String key = model.getName().replaceFirst("\\.model\\.json$", "");
            guard = ProjectSaveGuard.capture(storage, key, model, snap,
                    CuratableDomain.snapshotBeside(model), ManualCuration.fileForSnapshot(snap));
            project = new GeneratedProjectModelStore().load(model);
        }
        if (runLog != null && project == null) {
            throw new IOException("No saved model at " + model);
        }
        var saved = ProjectLoad.read(snap);
        var effective = project == null ? null : project.copy();
        if (effective != null) DomainSaver.addSnapshotSubclasses(effective, saved.fieldGraph());
        // Authored group records are snapshot presentation data, not instances of a
        // project class. Project their ordinary members with the rest of the instance
        // pool, but retain the group records for the inspection view.
        var projected = effective == null ? null : ProjectLoad.project(effective, storage, snap, saved,
                saved.objects().stream().filter(value -> !DynamicViewableGroup.isGroup(value)).toList());
        var loaded = runLog == null ? null
                : ProjectLoad.materialize(effective, storage, projected.detached(), runLog);
        Function<String, String> currentClass = projected == null ? Function.identity() : projected::currentClass;
        // Both views start with the same class claims. Only the inspection copy gets curation.
        var domain = materialize(snap, model, project, effective, guard,
                new ArrayList<>(saved.objects()), saved, currentClass, ManualCuration.forSnapshot(snap, guard));
        if (guard != null) guard.check();
        return new OpenedRun(domain, loaded);
    }

    public static boolean hasCuration(File snapshot) {
        return ManualCuration.fileForSnapshot(snapshot).isFile()
                || CorrectionsSidecar.beside(snapshot, ".autofix.json").isFile();
    }

    /** Apply the same presentation overlay to a newly accepted run, not its raw pool. */
    public static DomainModel inspectRun(File snapshot, GeneratedProjectModel model,
            GenerationRun run) {
        return materialize(snapshot, null, null, model, null,
                PoolCopy.deepCopy(run.dynamicObjects()), null, Function.identity(),
                ManualCuration.forSnapshot(snapshot, null));
    }

    /** The accepted run takes over the workbench's data but not its working configuration. */
    public static DomainModel inspectWorkingRun(ProjectBacking project, GenerationRun run) {
        var snapshot = project.snapshotFile();
        var view = materialize(snapshot, project.modelFile(), project.projectModel(),
                run.modelSnapshot(), project.saveGuard(), PoolCopy.deepCopy(run.dynamicObjects()), null,
                Function.identity(), ManualCuration.forSnapshot(snapshot, project.saveGuard()));
        if (project instanceof CuratableDomain previous) view.carryWorkingChanges(previous);
        else project.unsavedModelChanges().forEach(view::recordModelChange);
        return view;
    }

    private static CuratableDomain materialize(File snap, File model, GeneratedProjectModel project,
            GeneratedProjectModel effectiveProject, ProjectSaveGuard guard,
            List<WikidataDynamicObject> pool, LoadedSnapshot loaded, Function<String, String> currentClass,
            ManualCuration curation) {
        var fieldGraph = loaded == null ? null : loaded.fieldGraph();
        var roleSelections = loaded == null ? java.util.Map.<String, List<WikidataDynamicObject>>of()
                : loaded.roleSelections();
        java.util.function.Function<String, String> baseType = effectiveProject == null
                ? fieldGraph::baseType
                : type -> {
                    GeneratedClassModel declared = effectiveProject.findClass(type);
                    return declared == null ? null : declared.baseClassName();
                };
        ProductSchema compiled = effectiveProject == null
                ? null : ProductCompiler.classSchemas(effectiveProject);
        Function<String, FieldSchema> schemas = compiled == null
                ? type -> fieldGraph.fieldSchema(type, Set.of()) : compiled::fieldSchema;
        // Overlay curated / auto-fixed values onto the freshly loaded base data,
        // before compiling — so the sidecar survives regeneration. See domain.curation.
        // Manual values override; generated fills (e.g. <name>.autofix.json from a
        // fallback rule) only fill fields still absent.
        List<WikidataDynamicObject> curatedInstances =
                CuratedInstances.apply(pool, curation.instances(), baseType,
                        type -> CuratableDomain.fieldSchemaWithCuration(
                                type, schemas.apply(type), curation)).instances();
        var autofix = domain.curation.CorrectionsSidecar.source(
                domain.curation.CorrectionsSidecar.beside(snap, ".autofix.json"));
        domain.curation.Corrections.apply(pool, List.of(curation, autofix));
        // Fold curated duplicates into their primaries (Tanzania ≈ "Tanzania, United
        // Republic of") on the same overlay basis — re-applied every load, no snapshot edit.
        domain.curation.Merges.apply(pool, curation.merges(), baseType);

        DomainModel base;
        if (effectiveProject == null) {
            base = new SnapshotDomain(pool, fieldGraph, Set.of(), roleSelections);
        } else {
            BareReferenceCollapse.apply(pool);
            base = ProductCompiler.compile(effectiveProject, pool, roleSelections, compiled);
        }
        // Carry the curation store so the workbench can offer a "Curate…" action.
        java.util.List<objectview.viewconfig.DomainGroupRoot> savedGroupRoots =
                new java.util.ArrayList<>();
        if (loaded != null) loaded.groupRootBindings().forEach(binding -> {
            objectview.group.ViewableGroup<?> root =
                    DynamicViewableGroup.adapt(binding.root());
            if (root != null) savedGroupRoots.add(
                    new objectview.viewconfig.DomainGroupRoot(currentClass.apply(binding.memberType()), root));
        });
        // A saved TransformApp root holds only what was authored there; the model's
        // configured groups are rebuilt from this load's instances and added beside it.
        java.util.List<objectview.viewconfig.DomainGroupRoot> groupRoots =
                ConfiguredGroups.combine(
                        base.groupRootBindings(), savedGroupRoots);
        java.util.List<objectview.Viewable> memberRoots = new java.util.ArrayList<>();
        if (loaded == null) memberRoots.addAll(base.memberRoots());
        else {
            var admitted = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<WikidataDynamicObject, Boolean>());
            admitted.addAll(pool);
            loaded.memberRoots().stream().filter(admitted::contains)
                    .filter(WikidataDynamicObject::hasTypeStamp).forEach(memberRoots::add);
        }
        for (WikidataDynamicObject value : curatedInstances) {
            if (!memberRoots.contains(value)) memberRoots.add(value);
        }
        var domain = new CuratableDomain(
                base, curation, memberRoots, groupRoots,
                project == null ? null : model, project, guard);
        CuratedInstances.attachSchemas(curatedInstances, domain::fieldSchema);
        return domain;
    }

    /**
     * The saved projects the registry does not list.
     *
     * <p>A project is model-backed because its files say so, not because a registry row
     * was written. {@link dataset.DomainStorage#modelBackedNames()} already answers that
     * from the registry AND the models on disk; this catalog was asking the registry
     * alone. Historical Positions was therefore invisible here while sitting in
     * {@code data/wikidata/historicalpositions/}, so the only way to open it was through
     * a detached snapshot — and a working set opened that way has no owning project, so
     * saving it wrote another detached export instead of the project's own files.
     *
     * <p>A project whose own instances were discarded is still listed, opened on the
     * latest same-named transform export, so an explicit Save can consolidate it back
     * into the project it belongs to.
     */
    static List<DomainEntry> unregisteredProjects(
            dataset.DomainStorage storage, java.util.Set<String> already,
            java.util.function.Function<String, File> exportFor) {
        return unregisteredProjects(storage, already, exportFor, DomainCatalog::open);
    }

    private static List<DomainEntry> unregisteredProjects(DomainStorage storage,
            Set<String> already, Function<String, File> exportFor, ProjectOpener opener) {
        List<DomainEntry> out = new ArrayList<>();
        for (String name : storage.modelBackedNames()) {
            if (already.contains(name)) continue;
            File model = storage.modelFileOf(name);
            if (model == null || !model.isFile()) continue;
            File own = storage.snapshotFile(name);
            File snapshot = own.isFile() ? own : exportFor.apply(name);
            if (!snapshot.isFile()) continue;
            out.add(new DomainEntry(name, "generated",
                    "Load domain \"" + name + "\", its instances and saved model from "
                            + snapshot.getPath() + " and " + model.getPath() + ".",
                    () -> opener.open(snapshot, model, storage)));
        }
        return out;
    }
}
