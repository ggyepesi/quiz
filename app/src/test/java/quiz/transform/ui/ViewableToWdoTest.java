package quiz.transform.ui;

import aux.FlexibleDate;
import objectview.media.ImagePane;
import objectview.annotations.Link;
import objectview.annotations.Minor;
import objectview.render.Card;
import objectview.render.LinkRow;
import objectview.render.RenderContext;
import objectview.viewconfig.ViewConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.ViewableGroup;
import objectview.ViewableAdapter;
import quiz.transform.app.SnapshotDomain;
import quiz.transform.app.ViewableToWdo;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    static class EmptyChild extends ViewableAdapter {
        private final String label = "";

        @Override public String getIdentifier() { return "child"; }
        @Override public String getDisplayName() { return "Child"; }
    }

    static class DeclaredEntity extends ViewableAdapter {
        private final String name;
        private FlexibleDate admissionDate;
        @Link(text = "website")
        private final String website = "https://example.test";
        @Minor
        @Link(text = "minor website")
        private final String minorWebsite =
                "https://minor.example.test";
        private final List<EmptyChild> children = new java.util.ArrayList<>();
        private final List<ImagePane> images = new java.util.ArrayList<>();

        DeclaredEntity(String name) { this.name = name; }
        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
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

    @Test void qualifiesSameNamedGroupsByTheirParentPath(
            @TempDir Path dir) throws Exception {
        ViewableGroup b = new ViewableGroup("B");
        ViewableGroup bA = b.getOrCreateChild("A");
        ViewableGroup c = new ViewableGroup("C");
        ViewableGroup cA = c.getOrCreateChild("A");

        assertEquals("B.A", bA.getIdentifier());
        assertEquals("C.A", cA.getIdentifier());
        assertEquals("B/A", bA.getReferenceLabel());
        assertEquals("C/A", cA.getReferenceLabel());

        GroupedEntity grouped = new GroupedEntity("grouped");
        grouped.groups.put(bA.getIdentifier(), bA);
        grouped.groups.put(cA.getIdentifier(), cA);
        bA.addMember(grouped);
        cA.addMember(grouped);

        List<WikidataDynamicObject> roots = ViewableToWdo.pool(List.of(grouped));
        @SuppressWarnings("unchecked")
        List<WikidataDynamicObject> groups =
                new java.util.ArrayList<>(((java.util.Map<String,
                        WikidataDynamicObject>) roots.get(0).get("groups")).values());
        assertEquals(2, groups.size());
        assertEquals(List.of("A", "A"), groups.stream()
                .map(WikidataDynamicObject::getDisplayName).toList());
        assertEquals(List.of("B.A", "C.A"), groups.stream()
                .map(WikidataDynamicObject::qid).toList());
        assertTrue(groups.stream().noneMatch(WikidataDynamicObject::isValueObject));
        assertTrue(groups.stream().allMatch(
                group -> "ViewableGroup".equals(group.typeName())));
        assertEquals(List.of("B/A", "C/A"), groups.stream()
                .map(WikidataDynamicObject::getReferenceLabel).toList());
        for (WikidataDynamicObject group : groups) {
            assertTrue(group.get("parent") instanceof WikidataDynamicObject);
            assertTrue(group.get("children") instanceof java.util.Map<?, ?>);
            assertTrue(group.get("members") instanceof java.util.Map<?, ?>);
        }

        File file = dir.resolve("groups.snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store =
                new WikidataDynamicObjectJsonStore();
        store.save(roots, file);
        var loaded = store.loadAllWithFieldGraph(file);
        assertFalse(loaded.fieldGraph().memberTypes().contains("ViewableGroup"),
                "a reachable ViewableGroup is group structure, not a top-level member"
                        + " type — member types are the explicit roots only");

        WikidataDynamicObject loadedBA = loaded.objects().stream()
                .filter(object -> "ViewableGroup".equals(object.typeName())
                        && "B.A".equals(object.qid()))
                .findFirst().orElseThrow(() -> new AssertionError(
                        loaded.objects().stream()
                                .map(object -> object.typeName() + ":" + object.qid())
                                .toList().toString()));
        assertEquals("B", ((WikidataDynamicObject)
                loadedBA.get("parent")).qid());
        assertEquals("B/A", loadedBA.getReferenceLabel());
        assertEquals("grouped", ((java.util.Map<?, ?>) loadedBA.get("members"))
                .values().stream()
                .map(WikidataDynamicObject.class::cast)
                .findFirst().orElseThrow().qid());
    }

    @Test void preservesMapKeys() {
        GroupedEntity grouped = new GroupedEntity("grouped");
        grouped.groups.put("meaningful-key", new ViewableGroup("A"));

        WikidataDynamicObject converted =
                ViewableToWdo.pool(List.of(grouped)).get(0);

        assertTrue(converted.get("groups") instanceof java.util.Map<?, ?>);
        assertTrue(((java.util.Map<?, ?>) converted.get("groups"))
                .containsKey("meaningful-key"));
    }

    @Test
    void declaredNullAndEmptyFieldShapesSurviveSnapshotRoundTrip(
            @TempDir Path dir) throws Exception {
        DeclaredEntity entity = new DeclaredEntity("one");
        ReflectionDomain live = new ReflectionDomain(List.of(entity));
        File file = dir.resolve("declared-shape.snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store =
                new WikidataDynamicObjectJsonStore();

        store.saveWithFieldGraph(
                ViewableToWdo.pool(List.of(entity)), file, live);
        var loaded = store.loadAllWithFieldGraph(file);
        assertTrue(objectview.field.FieldSet.of(loaded.objects().get(0))
                        .field("website").link(),
                "a loaded dynamic object carries its persisted annotation schema");
        SnapshotDomain roundTripped =
                new SnapshotDomain(loaded.objects(), loaded.fieldGraph());

        var fields = roundTripped.fields("DeclaredEntity").stream()
                .map(DomainField::field).toList();
        assertTrue(fields.contains("admissionDate"), fields.toString());
        assertTrue(fields.contains("children"), fields.toString());
        assertTrue(fields.contains("children.label"), fields.toString());
        assertTrue(fields.contains("images"), fields.toString());

        var types = roundTripped.fieldTypes("DeclaredEntity");
        assertEquals("FlexibleDate",
                types.field("admissionDate").typeLabel());
        assertNotNull(types.field("children").nested(),
                "empty Viewable collections remain expandable");
        assertEquals("Collection<EmptyChild>",
                types.field("children").typeLabel());
        assertEquals("Collection<ImagePane>",
                types.field("images").typeLabel());
        assertTrue(roundTripped.fieldSchema("DeclaredEntity")
                .field("website").link(),
                "render hints must survive the typed-to-dynamic round trip");
        assertEquals("website", roundTripped.fieldSchema("DeclaredEntity")
                .field("website").linkText());
        assertTrue(roundTripped.fieldSchema("DeclaredEntity")
                        .field("minorWebsite").minor(),
                "@Minor must survive the typed-to-dynamic round trip");

        WikidataDynamicObject loadedEntity = roundTripped.instances().stream()
                .map(WikidataDynamicObject.class::cast)
                .filter(object -> "DeclaredEntity".equals(object.typeName()))
                .findFirst().orElseThrow();
        RenderContext context = new RenderContext(
                roundTripped.instances());
        context.setFieldSchemaResolver(
                value -> roundTripped.fieldSchema(value.typeName()));

        Card[] defaultCard = new Card[1];
        Card[] detailedCard = new Card[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            defaultCard[0] = new Card(
                    loadedEntity,
                    ViewConfig.all(WikidataDynamicObject.class),
                    context, false);
            detailedCard[0] = new Card(
                    loadedEntity,
                    ViewConfig.allWithMinorFields(
                            WikidataDynamicObject.class),
                    context, false);
        });
        assertEquals(1, count(defaultCard[0], LinkRow.class),
                "default snapshot cards must hide minor fields");
        assertEquals(2, count(detailedCard[0], LinkRow.class),
                "detail snapshot cards may opt minor fields in");
    }

    private static int count(Component root, Class<?> type) {
        int result = type.isInstance(root) ? 1 : 0;
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                result += count(child, type);
            }
        }
        return result;
    }
}
