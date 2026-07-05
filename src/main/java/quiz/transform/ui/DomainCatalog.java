package quiz.transform.ui;

import quiz.DatasetRegistry;
import quiz.QuizFactory;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The catalog of domains available to the transform workbench — BOTH the generated
 * Wikidata datasets ({@link DatasetRegistry}) and the built-in hand-written Quizable
 * domains (Nobel, State, SportTeam, … via {@link QuizFactory#builtInDomains()}).
 * Each entry opens lazily to a {@link DomainModel}, so listing is cheap and the
 * (possibly heavy) load happens on selection.
 *
 * <p>Unifying both behind {@link DomainModel} is the seam that also lets the web
 * server serve the built-in domains alongside the generated ones — iterate
 * {@link #all()} and serve each entry's instances.
 */
public final class DomainCatalog {

    private DomainCatalog() {}

    public interface Opener {
        DomainModel open() throws Exception;
    }

    public record Entry(String name, String source, Opener opener) {
        @Override public String toString() {
            return "[" + source + "]  " + name;
        }
    }

    public static List<Entry> all() {
        List<Entry> out = new ArrayList<>();

        // Generated Wikidata datasets (skip any whose snapshot isn't on disk).
        for (DatasetRegistry.Dataset d : DatasetRegistry.load().datasets()) {
            File snap = new File(d.snapshotPath());
            if (snap.isFile()) {
                out.add(new Entry(d.name(), "generated",
                        () -> new SnapshotDomain(
                                new WikidataDynamicObjectJsonStore().loadAll(snap))));
            }
        }

        // Built-in hand-written Quizable domains.
        for (QuizFactory.BuiltInDomain b : QuizFactory.builtInDomains()) {
            out.add(new Entry(b.icon() + " " + b.name(), "built-in",
                    () -> ReflectionDomain.of(b.views())));
        }

        return out;
    }
}
