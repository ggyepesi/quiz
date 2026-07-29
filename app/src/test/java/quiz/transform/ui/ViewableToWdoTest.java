package quiz.transform.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.ViewableGroup;
import objectview.ViewableAdapter;
import quiz.transform.app.ViewableToWdo;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hand-written Viewable graph converts to a savable WDO pool and round-trips
 * through the snapshot store — so a transform result over the built-in domains
 * (Nobel/State/…) can be persisted as a first-class domain.
 */
class ViewableToWdoTest {

    static class Person extends ViewableAdapter {
        private final String personName;
        private Person friend;
        Person(String name) { this.personName = name; }
        @Override public String getIdentifier() { return personName; }
        @Override public String getDisplayName() { return personName; }
    }

    static class NamedEntity extends ViewableAdapter {
        private final String id;
        private final String name;
        private final String type;

        NamedEntity(String id, String name, String type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }

        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return name; }
        @Override public String typeName() { return type; }
    }

    static class GroupedEntity extends ViewableAdapter {
        private final String id;
        private final java.util.Map<String, ViewableGroup> groups =
                new java.util.LinkedHashMap<>();

        GroupedEntity(String id) {
            this.id = id;
        }

        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return id; }
    }

    @Test void convertsAndRoundTripsAReferenceGraph(@TempDir Path dir) throws Exception {
        Person bob = new Person("Bob");
        Person alice = new Person("Alice");
        alice.friend = bob;

        List<WikidataDynamicObject> pool = ViewableToWdo.pool(List.of(alice));
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

    @Test void acceptsConsistentCopiesAndCrossTypeClaimsForOneId() {
        List<WikidataDynamicObject> pool = ViewableToWdo.pool(List.of(
                new NamedEntity("New Zealand", "New Zealand", "State"),
                new NamedEntity("New Zealand", "New Zealand", "Region"),
                new NamedEntity("New Zealand", "New Zealand", "Region")));
        assertEquals(3, pool.size());
    }

    @Test void rejectsConflictingNamesForTheSameLogicalTypeAndId() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ViewableToWdo.pool(List.of(
                        new NamedEntity("same-id", "First", "Person"),
                        new NamedEntity("same-id", "Second", "Person"))));
        assertTrue(error.getMessage().contains("conflicting typed identifiers"));
    }

    @Test void qualifiesSameNamedGroupsByTheirParentPath() {
        ViewableGroup b = new ViewableGroup("B");
        ViewableGroup bA = b.getOrCreateChild("A");
        ViewableGroup c = new ViewableGroup("C");
        ViewableGroup cA = c.getOrCreateChild("A");

        assertEquals("B.A", bA.getIdentifier());
        assertEquals("C.A", cA.getIdentifier());

        GroupedEntity grouped = new GroupedEntity("grouped");
        grouped.groups.put(bA.getIdentifier(), bA);
        grouped.groups.put(cA.getIdentifier(), cA);

        List<WikidataDynamicObject> roots = ViewableToWdo.pool(List.of(grouped));
        assertEquals(List.of(
                List.of("B", "A"),
                List.of("C", "A")), roots.get(0).get("groups"));
    }
}
