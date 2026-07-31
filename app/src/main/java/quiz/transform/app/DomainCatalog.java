package quiz.transform.app;

import quiz.DatasetRegistry;
import quiz.QuizFactory;
import quiz.transform.ui.DomainEntry;
import quiz.transform.ui.ReflectionDomain;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the {@link DomainEntry} catalog for the navigator — the wikidata bridge
 * that knows both sources: the generated Wikidata datasets ({@link DatasetRegistry})
 * and the built-in hand-written Viewable domains ({@link QuizFactory#builtInDomains()}).
 * The UI stays independent of these; this class wires them together.
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

        for (QuizFactory.BuiltInDomain b : QuizFactory.builtInDomains()) {
            out.add(new DomainEntry(b.icon() + " " + b.name(), "built-in",
                    () -> ReflectionDomain.of(b.views())));
        }

        return out;
    }

    /**
     * Opens a generated dataset: when the model.json is readable, compile it +
     * the pool into a typed {@link ProductDomain} (model-authoritative schema);
     * otherwise fall back to the snapshot's persisted field graph. Pre-v3 snapshots
     * derive that graph once as a compatibility fallback.
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
        quiz.curation.Merges.apply(pool, curation.merges());

        quiz.transform.ui.DomainModel base =
                compile(model, pool, loaded.fieldGraph());
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
                base, curation, loaded.memberRoots(), groupRoots);
    }

    private static quiz.transform.ui.DomainModel compile(
            File model,
            java.util.List<wikidata.explore.extract.WikidataDynamicObject> pool,
            wikidata.explore.extract.SnapshotFieldGraph fieldGraph) {
        if (model != null && model.isFile()) {
            try {
                var project = new wikidata.explore.model.GeneratedProjectModelStore()
                        .load(model);
                return wikidata.explore.transform.ProductCompiler.compile(project, pool);
            } catch (Exception unreadable) {
                // Fall through to the instance-derived schema.
            }
        }
        return new SnapshotDomain(pool, fieldGraph);
    }
}
