package wikidata.explore.transform;

import quiz.curation.Correction;
import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.util.List;

/**
 * Dry run for the {@link FieldFallbackRule#oscarYear() Oscar year} fallback rule:
 * loads the snapshot pool, derives the winner year fills from Wikidata via the
 * generic {@link PropertyFallbackCorrectionSource}, and prints the result —
 * verifies the recovery before persistence/wiring is added.
 *
 * <pre>java ... wikidata.explore.transform.AwardYearFixMain [snapshot.json]</pre>
 */
public final class AwardYearFixMain {

    public static void main(String[] args) throws Exception {
        File snap = new File(args.length > 0 ? args[0]
                : "data/wikidata/oscarnominations/oscarnominations.snapshot.json");
        System.out.println("Snapshot: " + snap.getAbsolutePath());

        List<WikidataDynamicObject> pool = new WikidataDynamicObjectJsonStore().loadAll(snap);
        System.out.println("Pool entities: " + pool.size());

        try (WikidataSparqlClient client =
                     new WikidataSparqlClient("quiz-award-year-fix/1.0 (ggyepesi@gmail.com)")) {
            List<Correction> corrections =
                    new PropertyFallbackCorrectionSource(pool, client, FieldFallbackRule.oscarYear())
                            .log(System.out::println)
                            .corrections();

            System.out.println("\n--- sample fills (up to 20) ---");
            corrections.stream().limit(20).forEach(c ->
                    System.out.println("  " + label(pool, c.qid()) + "  year=" + c.value()));
            System.out.println("\nTotal fills: " + corrections.size());

            File sidecar = quiz.curation.CorrectionsSidecar.beside(snap, ".autofix.json");
            quiz.curation.CorrectionsSidecar.save(sidecar, corrections);
            System.out.println("Wrote " + corrections.size() + " corrections to " + sidecar);
        }
    }

    private static String label(List<WikidataDynamicObject> pool, String qid) {
        for (WikidataDynamicObject o : pool) {
            if (qid.equals(o.getIdentifier())) {
                Object cat = o.get("category");
                return o.getDisplayName() + " (" + (cat == null ? "?" : displayOf(cat)) + ")";
            }
        }
        return qid;
    }

    private static String displayOf(Object ref) {
        if (ref instanceof WikidataDynamicObject w) {
            return w.getDisplayName();
        }
        return String.valueOf(ref);
    }
}
