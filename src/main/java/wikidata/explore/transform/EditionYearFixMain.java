package wikidata.explore.transform;

import quiz.curation.Correction;
import quiz.curation.CorrectionsSidecar;
import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.util.List;

/**
 * Fills missing Oscar nomination years from the ceremony edition's date
 * ({@link ReferenceLookupRule#oscarEditionYear()}) and writes them to the
 * {@code <name>.autofix.json} overlay, applied on load by DomainCatalog. Needs a
 * snapshot whose Nominations carry the {@code edition} reference.
 *
 * <pre>java ... wikidata.explore.transform.EditionYearFixMain [snapshot.json]</pre>
 */
public final class EditionYearFixMain {

    public static void main(String[] args) throws Exception {
        File snap = new File(args.length > 0 ? args[0]
                : "data/wikidata/oscarnominations/oscarnominations.snapshot.json");
        System.out.println("Snapshot: " + snap.getAbsolutePath());

        List<WikidataDynamicObject> pool = new WikidataDynamicObjectJsonStore().loadAll(snap);
        System.out.println("Pool entities: " + pool.size());

        try (WikidataSparqlClient client =
                     new WikidataSparqlClient("quiz-edition-year-fix/1.0 (ggyepesi@gmail.com)")) {
            List<Correction> corrections =
                    new ReferenceLookupCorrectionSource(pool, client,
                            ReferenceLookupRule.oscarEditionYear())
                            .log(System.out::println)
                            .corrections();

            System.out.println("\n--- sample fills (up to 15) ---");
            corrections.stream().limit(15).forEach(c ->
                    System.out.println("  " + label(pool, c.qid()) + "  year=" + c.value()));

            File sidecar = CorrectionsSidecar.beside(snap, ".autofix.json");
            CorrectionsSidecar.save(sidecar, corrections);
            System.out.println("\nWrote " + corrections.size() + " corrections to " + sidecar);
        }
    }

    private static String label(List<WikidataDynamicObject> pool, String qid) {
        for (WikidataDynamicObject o : pool) {
            if (qid.equals(o.getIdentifier())) {
                Object ed = o.get("edition");
                return o.getDisplayName() + " (" + (ed instanceof WikidataDynamicObject w
                        ? w.getDisplayName() : ed) + ")";
            }
        }
        return qid;
    }
}
