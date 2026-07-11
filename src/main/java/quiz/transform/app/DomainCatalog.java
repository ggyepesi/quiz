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
 * and the built-in hand-written Quizable domains ({@link QuizFactory#builtInDomains()}).
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
     * otherwise fall back to {@link SnapshotDomain}'s instance-derived schema
     * (saved/derived domains have no model).
     */
    private static quiz.transform.ui.DomainModel open(File snap, File model)
            throws Exception {
        var pool = new WikidataDynamicObjectJsonStore().loadAll(snap);
        // Overlay curated / auto-fixed values onto the freshly loaded base data,
        // before compiling — so the sidecar survives regeneration. See quiz.curation.
        quiz.curation.Corrections.apply(pool,
                List.of(quiz.curation.ManualCuration.forSnapshot(snap)));
        if (model != null && model.isFile()) {
            try {
                var project = new wikidata.explore.model.GeneratedProjectModelStore()
                        .load(model);
                return wikidata.explore.transform.ProductCompiler.compile(project, pool);
            } catch (Exception unreadable) {
                // Fall through to the instance-derived schema.
            }
        }
        return new SnapshotDomain(pool);
    }
}
