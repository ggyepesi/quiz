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
                out.add(new DomainEntry(d.name(), "generated",
                        () -> new SnapshotDomain(
                                new WikidataDynamicObjectJsonStore().loadAll(snap))));
            }
        }

        for (QuizFactory.BuiltInDomain b : QuizFactory.builtInDomains()) {
            out.add(new DomainEntry(b.icon() + " " + b.name(), "built-in",
                    () -> ReflectionDomain.of(b.views())));
        }

        return out;
    }
}
