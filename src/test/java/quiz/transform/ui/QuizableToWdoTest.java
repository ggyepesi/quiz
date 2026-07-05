package quiz.transform.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.QuizableAdapter;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hand-written Quizable graph converts to a savable WDO pool and round-trips
 * through the snapshot store — so a transform result over the built-in domains
 * (Nobel/State/…) can be persisted as a first-class domain.
 */
class QuizableToWdoTest {

    static class Person extends QuizableAdapter {
        private final String personName;
        private Person friend;
        Person(String name) { this.personName = name; }
        @Override public String getIdentifier() { return personName; }
        @Override public String getDisplayName() { return personName; }
    }

    @Test void convertsAndRoundTripsAReferenceGraph(@TempDir Path dir) throws Exception {
        Person bob = new Person("Bob");
        Person alice = new Person("Alice");
        alice.friend = bob;

        List<WikidataDynamicObject> pool = QuizableToWdo.pool(List.of(alice));
        assertEquals(1, pool.size());
        WikidataDynamicObject a = pool.get(0);
        assertEquals("Person", a.typeName());
        assertEquals("Alice", a.get("personName"));
        assertTrue(a.get("friend") instanceof WikidataDynamicObject);

        File file = dir.resolve("people.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.save(pool, file);

        java.util.Map<String, WikidataDynamicObject> byId = new java.util.HashMap<>();
        for (WikidataDynamicObject o : store.loadAll(file)) byId.put(o.qid(), o);

        WikidataDynamicObject reloaded = byId.get("Alice");
        assertNotNull(reloaded);
        WikidataDynamicObject friend = (WikidataDynamicObject) reloaded.get("friend");
        assertNotNull(friend);
        assertEquals("Bob", friend.get("personName"));
    }
}
