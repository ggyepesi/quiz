package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.FakeWikidataSparqlClient;
import wikidata.api.FakeWikidataApiClient;
import wikidata.explore.extract.WikidataDynamicObject;

import wikidata.explore.model.RoleKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Diane Warren case (a false drop), traced on real recorded data.
 *
 * <p>Diane Warren (Q237354) has many DISTINCT Best Original Song (Q112243)
 * nominations, for different films. The real reify loads only nominee(P2453),
 * forWork(P1686), year(P585) — NOT the song(P2553) or edition(P805). So a
 * statement of hers that lacks P1686 becomes fully self-referential (nominee and
 * forWork both fall back to her), and the naive witness match — keying only on
 * category + "a real qualifier references the subject" — collapsed it against
 * ANOTHER of her Best Original Song nominations (Marshall's atom, whose P2453 is
 * Diane Warren), dropping a genuine, distinct nomination.
 *
 * <p>The fix (#99): the witness that names her is naming her through the
 * {@link RoleKind#IDENTITY} nominee role, not a {@link RoleKind#REFERENCE}
 * forWork — so it does NOT witness a denormalization and the sparse nomination
 * is correctly KEPT.
 */
class DianeWarrenTraceTest {

    private static WikidataDynamicObject member(String qid, String name) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, name);
        o.type("OscarNominations");
        return o;
    }

    @Test
    void distinctSongNominationsCollapseUnderTheWitnessMatch() {
        WikidataDynamicObject warren = member("Q237354", "Diane Warren");
        WikidataDynamicObject marshall = member("Q22078078", "Marshall");
        WikidataDynamicObject bestSong = new WikidataDynamicObject(
                "Q112243", "Academy Award for Best Original Song");
        bestSong.type("Category");

        // The real reify loads nominee(P2453), forWork(P1686), year(P585) only.
        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .entity("Q237354", "Diane Warren")
                .entity("Q22078078", "Marshall")
                .entity("Q112243", "Academy Award for Best Original Song")
                // A SPARSE Diane Warren nomination: only P2553(song)+P805(edition),
                // NEITHER of which the reify loads -> nominee & forWork fall back to her.
                .statement("Q237354", "P1411", "Q237354$sparse", "Q112243",
                        Map.of("P2553", List.of("Q124853393"),
                               "P805", List.of("Q85315079")))
                // A COMPLETE Diane Warren nomination (has forWork) -> not self-ref.
                .statement("Q237354", "P1411", "Q237354$complete", "Q112243",
                        Map.of("P1686", List.of("Q47499112"),
                               "P805", List.of("Q24636843")))
                // Marshall (the film) names Diane Warren as nominee via P2453.
                .statement("Q22078078", "P1411", "Q22078078$marshall", "Q112243",
                        Map.of("P2453", List.of("Q237354"),
                               "P805", List.of("Q24636843")));

        List<WikidataDynamicObject> pool =
                new ArrayList<>(List.of(warren, marshall, bestSong));

        QualifierLoadConfig load = new QualifierLoadConfig(
                "OscarNominations", "P1411", "__Nomination", "Nomination",
                "category", "", List.of(
                        new QualifierLoadConfig.Qualifier(
                                "P2453", "nominee", QualifierLoadConfig.Kind.ENTITY),
                        new QualifierLoadConfig.Qualifier(
                                "P1686", "forWork", QualifierLoadConfig.Kind.ENTITY)));

        new QualifierLoader().api(api)
                .enrich(pool, load, new FakeWikidataSparqlClient(), null);

        // nominee is the subject's OWN role (IDENTITY); forWork REFERENCEs a work.
        ReifyConstruct reify = new ReifyConstruct(
                "OscarNominations", "__Nomination", "Nomination", "source", "value", true,
                List.of(new ReifyConstruct.Role("nominee", "nominee", true, RoleKind.IDENTITY),
                        new ReifyConstruct.Role("forWork", "forWork", true, RoleKind.REFERENCE)),
                List.of());

        TransformEngine engine = new TransformEngine();
        List<WikidataDynamicObject> nominations = engine.applyReify(pool, reify);

        System.out.println("Diane Warren trace: kept=" + nominations.size()
                + " demoted=" + engine.demoted().size());
        engine.selfReferenceFindings().forEach(f -> System.out.println("  " + f));

        // All three statements promote to distinct nominations; the sparse one is
        // named only through the IDENTITY nominee role, so it is NOT witnessed.
        assertTrue(engine.demoted().isEmpty(),
                "the sparse Diane Warren nomination is kept — its only 'witness' "
                        + "names her through the IDENTITY nominee role, not forWork");
        assertFalse(nominations.isEmpty(), "nominations were promoted");
    }
}
