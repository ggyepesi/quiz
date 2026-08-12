package wikidata.explore.extract;

import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import wikidata.explore.extract.WikidataDynamicObject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Save must merge same-qid instances rather than keep the first-reached: a
 * field-poor reference copy (made outside the registry) must not overwrite the
 * rich carrier and drop its fields (e.g. a nominee's {@code type}).
 */
class WikidataDynamicObjectJsonStoreMergeTest {

    private static Object typeValue(WikidataDynamicObject o) {
        return o.get("type");
    }

    @Test
    void poorCopyDoesNotDropRichCarriersType(@TempDir Path dir) throws Exception {
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();

        // The type value (bare class stub, as `type` values are).
        WikidataDynamicObject song = new WikidataDynamicObject("Q7366", "song");

        // Rich carrier: the nominated work with its type.
        WikidataDynamicObject rich = new WikidataDynamicObject("Q1", "For Your Eyes Only");
        rich.type("OscarNominations");
        rich.merge("type", song);

        // Field-poor copy of the SAME qid, reached FIRST at save time.
        WikidataDynamicObject poor = new WikidataDynamicObject("Q1", "For Your Eyes Only");
        poor.put("wikidata", "https://www.wikidata.org/wiki/Q1");

        File file = dir.resolve("snap.json").toFile();
        store.save(List.of(poor, rich), file);

        List<WikidataDynamicObject> reloaded = store.loadAll(file);
        Map<String, WikidataDynamicObject> byQid = new java.util.HashMap<>();
        for (WikidataDynamicObject o : reloaded) byQid.put(o.getIdentifier(), o);

        WikidataDynamicObject q1 = byQid.get("Q1");
        assertNotNull(q1, "carrier must be saved");
        // Merged: keeps the real stamp, keeps the poor copy's field, and — the
        // point — keeps the rich carrier's `type` link.
        assertEquals("OscarNominations", q1.typeName());
        assertTrue(q1.dynamicFields().containsKey("wikidata"));

        Object type = typeValue(q1);
        assertNotNull(type, "type link must survive the round-trip");
        String typeQid = (type instanceof java.util.Collection<?> col)
                ? ((WikidataDynamicObject) col.iterator().next()).getIdentifier()
                : ((WikidataDynamicObject) type).getIdentifier();
        assertEquals("Q7366", typeQid);
        assertTrue(byQid.containsKey("Q7366"), "type value entity present");
    }

    @Test
    void sameIdDifferentTypesAndReferencesRemainDistinct(@TempDir Path dir) throws Exception {
        WikidataDynamicObject state = new WikidataDynamicObject("France", "France");
        state.type("State");
        state.typeKey("State");
        state.put("capital", "Paris");

        WikidataDynamicObject group = new WikidataDynamicObject("France", "France");
        group.type("ViewableGroup");
        group.typeKey("ViewableGroup");
        group.put("members", List.of("Metropolitan France"));

        // Two copies of one carrier force unionValues to compare the referenced values.
        WikidataDynamicObject first = new WikidataDynamicObject("root", "Root");
        first.type("Container");
        first.typeKey("Container");
        first.put("items", List.of(state));
        WikidataDynamicObject second = new WikidataDynamicObject("root", "Root");
        second.type("Container");
        second.typeKey("Container");
        second.put("items", List.of(group));

        File file = dir.resolve("typed.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.save(List.of(first, second), file);

        List<WikidataDynamicObject> loaded = store.loadAll(file);
        List<WikidataDynamicObject> france = loaded.stream()
                .filter(o -> "France".equals(o.getIdentifier()))
                .toList();
        assertEquals(2, france.size());
        assertTrue(france.stream().anyMatch(o -> "State".equals(o.typeKey())
                && "Paris".equals(o.get("capital"))));
        assertTrue(france.stream().anyMatch(o -> "ViewableGroup".equals(o.typeKey())
                && o.get("members") != null));

        WikidataDynamicObject root = loaded.stream()
                .filter(o -> "root".equals(o.getIdentifier()))
                .findFirst().orElseThrow();
        assertTrue(root.get("items") instanceof java.util.Collection<?>);
        assertEquals(2, ((java.util.Collection<?>) root.get("items")).size(),
                "typed values sharing an id must not be deduplicated");
    }

    @Test void objectEqualityUsesTheSameTypedIdentityAsSnapshots() {
        WikidataDynamicObject state =
                new WikidataDynamicObject("France", "France");
        state.type("State");
        WikidataDynamicObject group =
                new WikidataDynamicObject("France", "France");
        group.type("ViewableGroup");

        assertNotEquals(state, group);
        assertEquals(2, new java.util.LinkedHashSet<>(
                List.of(state, group)).size());
    }

    /**
     * A reify's internal subject load type is plumbing that the generation un-stamps. It
     * escaped anyway — 6,807 Nominees in the oscarnominations pool were saved carrying
     * {@code __subject_Nomination} as their most-specific class, which split one class into
     * two tabs and made the class the pool keyed on unstable. The save boundary now drops
     * such names whatever the generation did, since a saved pool is the wrong place to
     * discover that a path was missed.
     */
    @Test void aSavedPoolNeverCarriesAnInternalLoadType(@TempDir Path dir) throws Exception {
        WikidataDynamicObject nominee = new WikidataDynamicObject("Q72717", "Elia Kazan");
        nominee.type("__subject_Nomination");
        nominee.assignClass("Nominee");
        nominee.put("occupation", "director");

        File file = dir.resolve("leaked-plumbing.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.save(List.of(nominee), file);

        // The schema graph counted too: it recorded a TypeShape per class name, so the
        // plumbing reappeared as a type even once the entity itself was clean.
        assertFalse(java.nio.file.Files.readString(file.toPath()).contains("__subject_"),
                "no internal load type may reach the file — pool, classes or schema");
        WikidataDynamicObject loaded = store.loadAll(file).stream()
                .filter(o -> "Q72717".equals(o.getIdentifier())).findFirst().orElseThrow();
        assertEquals("Nominee", loaded.typeName(), "the real class becomes the carrier");
        assertEquals(java.util.Set.of("Nominee"), loaded.directClassNames());
        assertEquals("director", loaded.get("occupation"), "its fields survive");
    }

    /** Retracting a class is the only way back: type(null) leaves the name as a membership. */
    @Test void retractingAClassClearsTheCarrierAndTheMembership() {
        WikidataDynamicObject subject = new WikidataDynamicObject("Q72717", "Elia Kazan");
        subject.type("__subject_Nomination");
        subject.assignClass("Nominee");

        subject.type(null);
        assertTrue(subject.directClassNames().contains("__subject_Nomination"),
                "clearing the carrier alone leaves the membership behind");

        subject.removeClass("__subject_Nomination");
        assertEquals(java.util.Set.of("Nominee"), subject.directClassNames());
        assertEquals("Nominee", subject.typeName());
    }

    @Test void oneEntityWithTwoUnrelatedRolesRoundTripsOnce(@TempDir Path dir)
            throws Exception {
        WikidataDynamicObject shared = new WikidataDynamicObject("Q42", "Shared entity");
        shared.type("Nominee");
        shared.assignClass("ForWork");
        shared.put("occupation", "actor");
        shared.put("genre", "drama film");

        WikidataDynamicObject nomination = new WikidataDynamicObject("Q1$stmt", "Nomination");
        nomination.type("Nomination");
        nomination.put("nominee", shared);
        nomination.put("forWork", shared);

        File file = dir.resolve("overlapping-roles.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.save(List.of(nomination), file);

        List<WikidataDynamicObject> loaded = store.loadAll(file);
        List<WikidataDynamicObject> q42s = loaded.stream()
                .filter(o -> "Q42".equals(o.getIdentifier())).toList();
        assertEquals(1, q42s.size(), "semantic roles must not create duplicate entities");
        WikidataDynamicObject restored = q42s.get(0);
        assertEquals("Nominee", restored.typeKey(), "the stable storage identity survives");
        assertEquals(java.util.Set.of("Nominee", "ForWork"), restored.directClassNames());
        assertEquals("actor", restored.get("occupation"));
        assertEquals("drama film", restored.get("genre"));

        WikidataDynamicObject restoredNomination = loaded.stream()
                .filter(o -> "Q1$stmt".equals(o.getIdentifier())).findFirst().orElseThrow();
        assertTrue(restoredNomination.get("nominee") == restoredNomination.get("forWork"),
                "both contextual roles must resolve to the same canonical object");
    }
}
