package quiz.curation;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Accepted proposed instances become ordinary typed members of the existing object graph. */
class CuratedInstancesTest {

    @Test void aReviewedEpisodeRetainsItsTextAndTypedCharacterReference() {
        WikidataDynamicObject achilles = new WikidataDynamicObject("Q41746", "Achilles");
        achilles.type("Character");
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(achilles));
        CuratedInstance episode = new CuratedInstance(
                "Episode", "wikipedia-passage:abc", "Achilles — Myths",
                List.of(
                        CuratedInstance.FieldValue.literal(
                                "summary", "Achilles was hidden on Skyros."),
                        CuratedInstance.FieldValue.references("characters",
                                List.of(new CuratedInstance.Reference(
                                        "Character", "Q41746")))),
                null);

        WikidataDynamicObject created = CuratedInstances.apply(pool, List.of(episode))
                .instances().getFirst();

        assertEquals(2, pool.size());
        assertEquals(java.util.Set.of("Episode"), created.directClassNames());
        assertEquals("Achilles was hidden on Skyros.", created.get("summary"));
        assertSame(achilles,
                ((List<?>) created.get("characters")).getFirst());
    }

    /** The other side: a curated reference to an entity no longer loaded (here the
     * Character was regenerated away) used to add a shell named by its QID and stamped
     * Character, which then served as a member. It stays unresolved and is reported. */
    @Test void aReferenceToAnAbsentEntityIsReportedNotInvented() {
        List<WikidataDynamicObject> pool = new ArrayList<>();
        CuratedInstance episode = new CuratedInstance(
                "Episode", "wikipedia-passage:abc", "Achilles — Myths",
                List.of(CuratedInstance.FieldValue.references("characters",
                        List.of(new CuratedInstance.Reference("Character", "Q41746")))),
                null);

        CuratedInstances.Result result = CuratedInstances.apply(pool, List.of(episode));

        assertEquals(1, pool.size(), "only the curated Episode enters the pool");
        assertEquals(null, result.instances().getFirst().get("characters"));
        assertEquals(List.of("Episode wikipedia-passage:abc.characters → Character Q41746"),
                result.unresolved());
    }
}
