package quiz.transform.app;

import wikidata.explore.extract.WikidataDynamicObject;

import quiz.DatasetRegistry;
import quiz.QuizFactory;
import quiz.transform.ui.DomainEntry;
import quiz.transform.ui.ReflectionDomain;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import domain.DomainModel;

/**
 * Assembles the {@link DomainEntry} catalog for the navigator from the saved Wikidata
 * datasets ({@link DatasetRegistry}). The hand-written domains (States, Oscars, …) are no
 * longer listed as live built-ins: once exported via "Save as domain" they are served from
 * their saved snapshots like every other dataset (their Java builders stay in QuizFactory
 * for re-export). The UI stays independent of this.
 */
public final class DomainCatalog {

    private DomainCatalog() {}

    public static List<DomainEntry> all() {
        List<DomainEntry> out = new ArrayList<>();
        List<DatasetRegistry.Dataset> saved = DatasetRegistry.load().datasets();
        java.util.Set<DatasetRegistry.Dataset> consumed =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        for (DatasetRegistry.Dataset d : saved) {
            if (consumed.contains(d)) continue;
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
            File snap = new File(snapshotOwner.snapshotPath());
            if (snap.isFile()) {
                File model = new File(modelOwner == null ? "" : modelOwner.modelPath());
                File selectedSnapshot = snap;
                File selectedModel = model;
                out.add(new DomainEntry(d.name(), "generated",
                        "Load domain \"" + d.name() + "\", its instances and saved model from "
                                + snap.getPath() + (model.isFile()
                                ? " and " + model.getPath() : "") + ".",
                        () -> open(selectedSnapshot, selectedModel)));
            }
            consumed.add(d);
        }

        // Re-wired: list the hand-written domains as LIVE ReflectionDomains again, so each can
        // be opened on the current code and re-exported via "Save as domain" — producing fresh
        // snapshots that match the live field model (no stale-schema translation).
        for (QuizFactory.BuiltInDomain b : QuizFactory.builtInDomains()) {
            out.add(new DomainEntry(b.icon() + " " + b.name(), "built-in",
                    "Load built-in domain \"" + b.name() + "\" from the running application.",
                    () -> ReflectionDomain.of(b.views())));
        }

        return out;
    }

    /**
     * Opens a generated dataset: when model.json exists, compile it with the pool
     * into a typed {@link ProductDomain} (model-authoritative schema); a dataset
     * without a model uses the snapshot's persisted field graph.
     */
    private static DomainModel open(File snap, File model)
            throws Exception {
        var loaded = new WikidataDynamicObjectJsonStore()
                .loadAllWithFieldGraph(snap);
        var pool = loaded.objects();
        // Overlay curated / auto-fixed values onto the freshly loaded base data,
        // before compiling — so the sidecar survives regeneration. See quiz.curation.
        // Manual values override; generated fills (e.g. <name>.autofix.json from a
        // fallback rule) only fill fields still absent.
        var curation = quiz.curation.ManualCuration.forSnapshot(snap);
        var autofix = quiz.curation.CorrectionsSidecar.source(
                quiz.curation.CorrectionsSidecar.beside(snap, ".autofix.json"));
        quiz.curation.Corrections.apply(pool, List.of(curation, autofix));
        // Fold curated duplicates into their primaries (Tanzania ≈ "Tanzania, United
        // Republic of") on the same overlay basis — re-applied every load, no snapshot edit.
        quiz.curation.Merges.apply(
                pool, curation.merges(), loaded.fieldGraph()::baseType);

        DomainModel base =
                compile(model, pool, loaded.fieldGraph(), loaded.roleSelections());
        // Carry the curation store so the workbench can offer a "Curate…" action.
        java.util.List<objectview.viewconfig.DomainGroupRoot> groupRoots =
                loaded.groupRootBindings().stream()
                        .flatMap(binding -> {
                            objectview.group.ViewableGroup<?> root =
                                    DynamicViewableGroup.adapt(binding.root());
                            return root == null ? java.util.stream.Stream.empty()
                                    : java.util.stream.Stream.of(
                                            new objectview.viewconfig.DomainGroupRoot(
                                                    binding.memberType(), root));
                        })
                        .toList();
        return new CuratableDomain(
                base, curation, loaded.memberRoots(), groupRoots,
                model != null && model.isFile() ? model : null);
    }

    private static DomainModel compile(
            File model,
            java.util.List<wikidata.explore.extract.WikidataDynamicObject> pool,
            wikidata.explore.extract.SnapshotFieldGraph fieldGraph,
            java.util.Map<String, java.util.List<wikidata.explore.extract.WikidataDynamicObject>>
                    roleSelections) throws Exception {
        if (model != null && model.isFile()) {
            var project = new wikidata.explore.model.GeneratedProjectModelStore()
                    .load(model);
            // A pre-fix TransformApp snapshot can already carry semantic subclasses
            // that its model save lost. Make them visible in this working session; the
            // corrected Save model action persists them into the owner.
            DomainSaver.addSubclasses(project,
                    new SnapshotDomain(pool, fieldGraph, java.util.Set.of(), roleSelections));
            return wikidata.explore.transform.ProductCompiler.compile(
                    project, pool, roleSelections);
        }
        return new SnapshotDomain(pool, fieldGraph, java.util.Set.of(), roleSelections);
    }
}
