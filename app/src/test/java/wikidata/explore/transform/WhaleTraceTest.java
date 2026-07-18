package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.FakeWikidataSparqlClient;
import wikidata.api.FakeWikidataApiClient;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Whale, end-to-end, on real recorded Wikidata data through the fake service:
 *
 * <ul>
 *   <li>The Whale (Q105883400) has a BARE {@code P1411 -> Q106301} (Best Supporting
 *       Actress) — no qualifiers.</li>
 *   <li>Hong Chau (Q38195662) has {@code P1411 -> Q106301} with {@code forWork = The
 *       Whale}, the genuine nomination.</li>
 * </ul>
 *
 * Qualifier-load attaches both statements; reify promotes them to Nominations; the
 * film's fully self-referential atom is dropped, witnessed by Hong Chau's real one.
 */
class WhaleTraceTest {

    private static WikidataDynamicObject member(String qid, String name) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, name);
        o.type("OscarNominations");
        return o;
    }

    @Test
    void whaleFilmSelfNominationIsDroppedForHongChausRealOne() {
        WikidataDynamicObject film = member("Q105883400", "The Whale");
        WikidataDynamicObject hongChau = member("Q38195662", "Hong Chau");
        WikidataDynamicObject category = new WikidataDynamicObject("Q106301",
                "Academy Award for Best Supporting Actress");
        category.type("Category");

        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .entity("Q105883400", "The Whale")
                .entity("Q38195662", "Hong Chau")
                .entity("Q106301", "Academy Award for Best Supporting Actress")
                .entity("Q66707607", "95th Academy Awards")
                // The film's bare P1411 -> Best Supporting Actress (the phantom).
                .statement("Q105883400", "P1411", "Q105883400$whale", "Q106301", Map.of())
                // Hong Chau's real P1411 -> Best Supporting Actress, forWork = the film.
                .statement("Q38195662", "P1411", "Q38195662$real", "Q106301",
                        Map.of("P1686", List.of("Q105883400"),
                               "P805", List.of("Q66707607")));

        List<WikidataDynamicObject> pool =
                new ArrayList<>(List.of(film, hongChau, category));

        // Load the P1411 statements + qualifiers (forWork, edition) onto the members.
        QualifierLoadConfig load = new QualifierLoadConfig(
                "OscarNominations", "P1411", "__Nomination", "Nomination",
                "category", "", List.of(
                        new QualifierLoadConfig.Qualifier(
                                "P1686", "forWork", QualifierLoadConfig.Kind.ENTITY),
                        new QualifierLoadConfig.Qualifier(
                                "P805", "edition", QualifierLoadConfig.Kind.ENTITY)));

        new QualifierLoader().api(api)
                .enrich(pool, load, new FakeWikidataSparqlClient(), null);

        // Reify the loaded statements into Nominations, with subject-fallback ON for
        // every role — nominee (the subject's IDENTITY) / forWork / edition (REFERENCEs).
        // Here the witness (Hong Chau) references the film through forWork, a REFERENCE
        // role, so the film's bare atom IS a denormalized self-copy — contrast Diane Warren.
        ReifyConstruct reify = new ReifyConstruct(
                "OscarNominations", "__Nomination", "Nomination", "source", "value", true,
                List.of(new ReifyConstruct.Role("nominee", "nominee", true,
                                wikidata.explore.model.RoleKind.IDENTITY),
                        new ReifyConstruct.Role("forWork", "forWork", true),
                        new ReifyConstruct.Role("edition", "edition", true)),
                List.of());

        TransformEngine engine = new TransformEngine();
        List<WikidataDynamicObject> nominations = engine.applyReify(pool, reify, "category");

        // Trace: two statements loaded → two Nomination atoms built, the film's dropped.
        System.out.println("Whale trace: " + nominations.size()
                + " nominations kept, demoted=" + engine.demoted().size());
        engine.selfReferenceFindings().forEach(f -> System.out.println("  " + f));

        assertEquals(1, nominations.size(),
                "only Hong Chau's real nomination survives");
        assertTrue(nominations.contains(
                        findStatement(hongChau, "__Nomination")),
                "Hong Chau's real nomination is kept");
        assertFalse(nominations.contains(
                        findStatement(film, "__Nomination")),
                "the film's bare self-nomination phantom is dropped");
    }

    @SuppressWarnings("unchecked")
    private static WikidataDynamicObject findStatement(
            WikidataDynamicObject member, String field) {
        Object v = member.get(field);
        if (v instanceof List<?> list && !list.isEmpty()) {
            return (WikidataDynamicObject) list.get(0);
        }
        return (WikidataDynamicObject) v;
    }
}
