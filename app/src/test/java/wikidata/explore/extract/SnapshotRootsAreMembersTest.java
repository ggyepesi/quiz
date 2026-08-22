package wikidata.explore.extract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A snapshot's roots are its MEMBERS, which is not the same as everything in the pool.
 *
 * <p>Generated domains used to show non-member entities as top-level members with no type:
 * Oscar listed 38 of them — {@code human}, {@code film}, {@code album} — which are the class
 * VALUES of the auto-injected P31 field, pulled into the shared registry by the root query.
 * Every domain had them: periodictable None:104 against Element:105, mythology None:1541
 * against Character:500. The pool does double duty — materialize and serialize — and
 * serialization was rooting all of it, so "reachable from the registry" silently became
 * "is a member".
 *
 * <p>Membership is an explicit property: the type stamp, whose own contract says an unstamped
 * object is a reference, not a member. This is where that gets consulted, and it had been a
 * comment beside one boolean until the same confusion turned up again in the save guards —
 * {@code typeName()} answers with the carrier's Java class name when there is no stamp, so
 * only {@code hasTypeStamp()} can be asked.
 */
class SnapshotRootsAreMembersTest {

    @Test void aClassValueReferencedByAMemberIsKeptButIsNotAMember(@TempDir Path dir)
            throws Exception {
        // The exact shape from the bug: a member whose P31 field points at "film".
        WikidataDynamicObject film = new WikidataDynamicObject("Q11424", "film");
        WikidataDynamicObject movie = new WikidataDynamicObject("Q47703", "Blood Diamond");
        movie.type("Movie");
        movie.put("type", film);

        List<WikidataDynamicObject> loaded = roundTrip(dir, List.of(movie, film));

        assertEquals(List.of("Blood Diamond"), names(loaded),
                "the class value is not a top-level member");
        WikidataDynamicObject reloaded = loaded.getFirst();
        assertNotNull(reloaded.get("type"), "and is still reachable from the member");
        assertEquals("film",
                ((WikidataDynamicObject) reloaded.get("type")).getDisplayName());
    }

    @Test void everyStampedMemberIsStillARoot(@TempDir Path dir) throws Exception {
        WikidataDynamicObject movie = stamped("Q47703", "Blood Diamond", "Movie");
        WikidataDynamicObject star = stamped("Q37079", "Leonardo DiCaprio", "Person");

        assertEquals(List.of("Blood Diamond", "Leonardo DiCaprio"),
                names(roundTrip(dir, List.of(movie, star))));
    }

    /** Different classes, one unstamped value each — the periodictable/mythology shape. */
    @Test void aMultiClassDomainKeepsEveryMemberAndNoClassValue(@TempDir Path dir)
            throws Exception {
        WikidataDynamicObject element = new WikidataDynamicObject("Q11344", "chemical element");
        WikidataDynamicObject hydrogen = stamped("Q556", "hydrogen", "Element");
        hydrogen.put("type", element);
        WikidataDynamicObject group = stamped("Q83165", "group 1", "ElementGroup");
        group.put("type", element);

        List<WikidataDynamicObject> loaded = roundTrip(dir, List.of(hydrogen, group, element));

        assertEquals(List.of("hydrogen", "group 1"), names(loaded));
        assertFalse(names(loaded).contains("chemical element"));
    }

    /** Nothing to serve: a pool of pure reference targets has no members at all, and saying
     *  so is the honest answer — it used to say every one of them was a member. */
    @Test void aPoolOfNothingButReferenceTargetsHasNoMembers(@TempDir Path dir) throws Exception {
        List<WikidataDynamicObject> loaded = roundTrip(dir, List.of(
                new WikidataDynamicObject("Q11424", "film"),
                new WikidataDynamicObject("Q5", "human")));

        assertTrue(loaded.isEmpty());
    }

    /** The same rule guards group roots, for the same reason and in the same breath. */
    @Test void anUnstampedGroupRootIsNotRecordedEither(@TempDir Path dir) throws Exception {
        WikidataDynamicObject member = stamped("Q47703", "Blood Diamond", "Movie");
        WikidataDynamicObject unstampedGroup = new WikidataDynamicObject("G1", "By decade");
        WikidataDynamicObject stampedGroup = stamped("G2", "By country", "ViewableGroup");

        File file = dir.resolve("groups.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.saveWithGroupRootBindings(
                List.of(member),
                List.of(new WikidataDynamicObjectJsonStore.GroupRootBinding("Movie", unstampedGroup),
                        new WikidataDynamicObjectJsonStore.GroupRootBinding("Movie", stampedGroup)),
                file, null);

        List<String> groupRoots = groupRootKeys(file);
        assertEquals(1, groupRoots.size(), groupRoots.toString());
        assertTrue(groupRoots.getFirst().endsWith("G2"),
                "only the stamped group root is recorded: " + groupRoots);
    }

    /** Reads the persisted group-root keys straight out of the file. */
    private static List<String> groupRootKeys(File file) throws Exception {
        com.fasterxml.jackson.databind.JsonNode tree =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(file);
        com.fasterxml.jackson.databind.JsonNode roots = tree.get("groupRoots");
        // Jackson default typing writes a list as ["java.util.ArrayList", [ ... ]].
        if (roots != null && roots.isArray() && roots.size() == 2 && roots.get(0).isTextual()) {
            roots = roots.get(1);
        }
        List<String> keys = new java.util.ArrayList<>();
        if (roots != null) roots.forEach(key -> keys.add(key.asText()));
        return keys;
    }

    private static WikidataDynamicObject stamped(String qid, String name, String type) {
        WikidataDynamicObject object = new WikidataDynamicObject(qid, name);
        object.type(type);
        return object;
    }

    private static List<String> names(List<WikidataDynamicObject> objects) {
        return objects.stream().map(WikidataDynamicObject::getDisplayName).toList();
    }

    private static List<WikidataDynamicObject> roundTrip(
            Path dir, List<WikidataDynamicObject> pool) throws Exception {
        File file = dir.resolve("snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.save(pool, file);
        return store.load(file);
    }
}
