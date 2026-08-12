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

        for (DatasetRegistry.Dataset d : DatasetRegistry.load().datasets()) {
            File snap = new File(d.snapshotPath());
            if (snap.isFile()) {
                File model = new File(d.modelPath());
                out.add(new DomainEntry(d.name(), "generated",
                        () -> open(snap, model)));
            }
        }

        // Re-wired: list the hand-written domains as LIVE ReflectionDomains again, so each can
        // be opened on the current code and re-exported via "Save as domain" — producing fresh
        // snapshots that match the live field model (no stale-schema translation).
        for (QuizFactory.BuiltInDomain b : QuizFactory.builtInDomains()) {
            out.add(new DomainEntry(b.icon() + " " + b.name(), "built-in",
                    () -> ReflectionDomain.of(b.views())));
        }

        return out;
    }

    /**
     * Opens a generated dataset: when model.json exists, compile it with the pool
     * into a typed {@link ProductDomain} (model-authoritative schema); a dataset
     * without a model uses the snapshot's persisted field graph.
     */
    private static quiz.transform.ui.DomainModel open(File snap, File model)
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

        quiz.transform.ui.DomainModel base =
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

    private static quiz.transform.ui.DomainModel compile(
            File model,
            java.util.List<wikidata.explore.extract.WikidataDynamicObject> pool,
            wikidata.explore.extract.SnapshotFieldGraph fieldGraph,
            java.util.Map<String, java.util.List<wikidata.explore.extract.WikidataDynamicObject>>
                    roleSelections) throws Exception {
        if (model != null && model.isFile()) {
            var project = new wikidata.explore.model.GeneratedProjectModelStore()
                    .load(model);
            return wikidata.explore.transform.ProductCompiler.compile(
                    project, pool, roleSelections);
        }
        return new SnapshotDomain(pool, fieldGraph, java.util.Set.of(), roleSelections);
    }
}
