package domain.curation;

import org.junit.jupiter.api.Test;
import domain.runtime.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Accepted proposed instances become ordinary typed members of the existing object graph. */
class CuratedInstancesTest {

    @Test void aSingleReferenceDoesNotPickOneOfSeveralSavedIdentities() {
        var work = new WikidataDynamicObject("Q205882", "Prose Edda");
        work.type("Work");
        var source = new ValueSource("Wikisource (English)", "Prose Edda", "",
                "https://en.wikisource.org/wiki/Prose_Edda");
        var story = new CuratedInstance("Story", "story:1", "A story",
                List.of(CuratedInstance.FieldValue.references("work", List.of(
                        new CuratedInstance.Reference("Work", "Q205882"),
                        new CuratedInstance.Reference("Work", "Q999")))), source);
        var pool = new ArrayList<>(List.of(work));

        var result = CuratedInstances.apply(pool, List.of(story), type -> null,
                type -> () -> List.of(objectview.field.FieldRef.of("work",
                        objectview.field.FieldKind.REFERENCE, "Work", true, false, false)));

        assertNull(result.instances().getFirst().get("work"),
                "even when only one identity is loaded, do not guess which one was intended");
        var refusal = result.unresolved().getFirst();
        org.junit.jupiter.api.Assertions.assertTrue(refusal.ambiguous());
        assertEquals("story:1", refusal.instance().id());
        assertEquals(List.of("Q205882", "Q999"), refusal.targets().stream()
                .map(target -> target.reference().id()).toList());
        assertSame(work, refusal.targets().getFirst().loaded());
        assertNull(refusal.targets().getLast().loaded());
    }

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

    /** Reported shape: 439 story → NorseCharacter references were "nothing loaded" while
     * every one named a loaded NorseDeity or NorseGiant — subclasses of NorseCharacter. */
    @Test void aReferenceResolvesToALoadedSubclassInstanceButNotToAnUnrelatedClass() {
        WikidataDynamicObject loki = new WikidataDynamicObject("Q133147", "Loki");
        loki.type("NorseDeity");
        WikidataDynamicObject place = new WikidataDynamicObject("Q999", "Place");
        place.type("NorsePlace");
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(loki, place));
        java.util.Map<String, String> bases = java.util.Map.of(
                "NorseDeity", "NorseCharacter", "NorseCharacter", "Entity");
        CuratedInstance story = new CuratedInstance(
                "NorseStory", "text-passage:abc", "Gylfaginning 33",
                List.of(CuratedInstance.FieldValue.references("characters", List.of(
                        new CuratedInstance.Reference("NorseCharacter", "Q133147"),
                        new CuratedInstance.Reference("NorseCharacter", "Q999")))),
                null);

        CuratedInstances.Result result = CuratedInstances.apply(pool, List.of(story), bases::get);

        assertEquals(List.of(loki), result.instances().getFirst().get("characters"));
        assertEquals("Q999", result.unresolved().getFirst().targets().getFirst().reference().id(),
                "a NorsePlace is not a NorseCharacter");
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
        var refusal = result.unresolved().getFirst();
        org.junit.jupiter.api.Assertions.assertFalse(refusal.ambiguous());
        assertEquals("characters", refusal.fieldLabel());
        assertNull(refusal.targets().getFirst().loaded());
    }
}
