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
        for (WikidataDynamicObject o : store.loadAll(file)) byId.put(o.getIdentifier(), o);

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
                .map(WikidataDynamicObject::getIdentifier).toList());
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
                        && "B.A".equals(object.getIdentifier()))
                .findFirst().orElseThrow(() -> new AssertionError(
                        loaded.objects().stream()
                                .map(object -> object.typeName() + ":" + object.getIdentifier())
                                .toList().toString()));
        assertEquals("B", ((WikidataDynamicObject)
                loadedBA.get("parent")).getIdentifier());
        assertEquals("B/A", loadedBA.getReferenceLabel());
        assertEquals("grouped", ((java.util.Map<?, ?>) loadedBA.get("members"))
                .values().stream()
                .map(WikidataDynamicObject.class::cast)
                .findFirst().orElseThrow().getIdentifier());
    }

    @Test void aGroupBindingAndAMemberReferenceResolveToOneGroupObject() {
        // The "save manual States as domain" shape: a member references a
        // ViewableGroup, and the group-root binding is the transform-app's
        // EditableGroup COPY of the same root. Both paths must resolve to ONE
        // ⟨ViewableGroup, id⟩ — the bound (edited) tree is the one that survives,
        // and the member's reference resolves to it rather than adding a second
        // representation of the same group.
        ViewableGroup root = new ViewableGroup("All");
        ViewableGroup leaf = root.getOrCreateChild("Lozengy");
        GroupedEntity member = new GroupedEntity("m1");
        member.groups.put(leaf.getIdentifier(), leaf);

        quiz.transform.EditableGroup editableRoot =
                quiz.transform.EditableGroup.copyOf(root);
        var converted = ViewableToWdo.convertDomain(
                java.util.List.of(member),
                java.util.List.of(new objectview.viewconfig.DomainGroupRoot(
                        "GroupedEntity", editableRoot)),
                null);

        // A group is persisted under its logical class, never the Java subclass that
        // happens to carry it — otherwise editing a group would move its identity.
        assertTrue(converted.allObjects().stream()
                        .noneMatch(o -> "EditableGroup".equals(o.typeName())),
                "a group carrier's Java subclass must not become its persisted type");
        assertEquals("ViewableGroup",
                converted.groupRootBindings().get(0).root().typeName());
        // Exactly one group object per id (root + leaf) — no duplicate representation.
        assertEquals(2, converted.allObjects().stream()
                        .filter(o -> "ViewableGroup".equals(o.typeName())).count());
        // The member's own reference is that same object, not a second copy of it.
        assertTrue(((java.util.Map<?, ?>) converted.allObjects().stream()
                        .filter(o -> "m1".equals(o.getIdentifier()))
                        .findFirst().orElseThrow().get("groups")).values().stream()
                        .allMatch(referenced -> converted.allObjects().stream()
                                .anyMatch(o -> o == referenced)),
                "a member's group reference must be the converted group itself");
    }

    @Test void anEditAddedToTheBoundTreeSurvivesTheMemberReferencedOriginal() {
        // The type-spec-group shape: the loaded tree is still what members point at,
        // while the child the user just added exists only on the edited copy. Saving
        // must keep the edit AND leave one object per group id.
        ViewableGroup root = new ViewableGroup("All");
        ViewableGroup leaf = root.getOrCreateChild("Lozengy");
        GroupedEntity member = new GroupedEntity("m1");
        member.groups.put(leaf.getIdentifier(), leaf);

        quiz.transform.EditableGroup editableRoot =
                quiz.transform.EditableGroup.copyOf(root);
        quiz.transform.EditableGroup added = new quiz.transform.EditableGroup("Added");
        editableRoot.addGroup(added);

        var converted = ViewableToWdo.convertDomain(
                java.util.List.of(member),
                java.util.List.of(new objectview.viewconfig.DomainGroupRoot(
                        "GroupedEntity", editableRoot)),
                null);

        assertTrue(converted.allObjects().stream()
                        .anyMatch(o -> "All.Added".equals(o.getIdentifier())),
                "the added child must be saved");
        assertEquals(3, converted.allObjects().stream()
                        .filter(o -> "ViewableGroup".equals(o.typeName())).count(),
                "root + leaf + added, each exactly once");
    }

    @Test void aLoadedWdoMembersOldGroupReferenceIsReboundToTheEditedTree() {
        WikidataDynamicObject oldLeaf = new WikidataDynamicObject("All.People", "People");
        oldLeaf.type("ViewableGroup");
        oldLeaf.typeKey("ViewableGroup");
        WikidataDynamicObject member = new WikidataDynamicObject("m1", "m1");
        member.type("GroupedEntity");
        member.typeKey("GroupedEntity");
        member.put("groups", new java.util.LinkedHashMap<>(
                java.util.Map.of(oldLeaf.getIdentifier(), oldLeaf)));

        quiz.transform.EditableGroup editedRoot = new quiz.transform.EditableGroup("All");
        quiz.transform.EditableGroup editedLeaf = new quiz.transform.EditableGroup("People");
        editedRoot.addGroup(editedLeaf);

        var converted = ViewableToWdo.convertDomain(
                java.util.List.of(member),
                java.util.List.of(new objectview.viewconfig.DomainGroupRoot(
                        "GroupedEntity", editedRoot)), null);

        WikidataDynamicObject savedMember = converted.memberRoots().get(0);
        assertTrue(savedMember != member, "saving must not mutate the loaded snapshot carrier");
        Object savedReference = ((java.util.Map<?, ?>) savedMember.get("groups"))
                .get("All.People");
        assertTrue(savedReference == converted.allObjects().stream()
                        .filter(o -> "All.People".equals(o.getIdentifier()))
                        .findFirst().orElseThrow(),
                "the loaded back-reference must point at the edited group conversion");
        assertTrue(savedReference != oldLeaf,
                "the old snapshot group must not leak into the saved graph");
    }

    /** A group registers under NO member type when it is reached while walking a member,
     *  so every scan over the registry must tolerate an unscoped key. Two members sharing
     *  one group is all it takes to put such a key in front of the second one. */
    @Test void twoMembersMayShareAGroupThatNoBindingScopes() {
        ViewableGroup root = new ViewableGroup("All");
        ViewableGroup leaf = root.getOrCreateChild("Lozengy");
        GroupedEntity one = new GroupedEntity("m1");
        one.groups.put(leaf.getIdentifier(), leaf);
        GroupedEntity two = new GroupedEntity("m2");
        two.groups.put(leaf.getIdentifier(), leaf);

        List<WikidataDynamicObject> pool = ViewableToWdo.pool(List.of(one, two));

        assertEquals(2, pool.size());
        assertEquals(pool.get(0).get("groups"), pool.get(1).get("groups"),
                "both members reference the one converted group");
    }

    /** Without a bound tree for the member's type there is nothing authoritative to say
     *  a group was removed, so its references must survive the save untouched. */
    @Test void groupReferencesSurviveASaveThatBindsNoTree() {
        WikidataDynamicObject group = loadedGroup("All.People", "People");
        WikidataDynamicObject member = loadedMember("m1");
        member.put("groups", new java.util.LinkedHashMap<>(
                java.util.Map.of(group.getIdentifier(), group)));

        List<WikidataDynamicObject> pool = ViewableToWdo.pool(List.of(member), null);

        assertEquals(java.util.Set.of("All.People"),
                ((java.util.Map<?, ?>) pool.get(0).get("groups")).keySet(),
                "an unbound save must not drop what it cannot speak for");
    }

    /** A group is rebound because it IS a group, not because of the field name it sits
     *  under, and at any depth — otherwise a legacy group leaks into the saved graph. */
    @Test void aGroupIsReboundUnderAnyFieldNameAndAtAnyDepth() {
        WikidataDynamicObject oldGroup = loadedGroup("All.People", "People");
        WikidataDynamicObject nested = loadedMember("m3");
        nested.put("groups", new java.util.LinkedHashMap<>(
                java.util.Map.of(oldGroup.getIdentifier(), oldGroup)));
        WikidataDynamicObject member = loadedMember("m1");
        member.put("primaryGroup", oldGroup);
        member.put("colleague", nested);

        quiz.transform.EditableGroup editedRoot = new quiz.transform.EditableGroup("All");
        quiz.transform.EditableGroup editedLeaf =
                new quiz.transform.EditableGroup("People");
        editedRoot.addGroup(editedLeaf);
        var converted = ViewableToWdo.convertDomain(List.of(member),
                List.of(new objectview.viewconfig.DomainGroupRoot(
                        "GroupedEntity", editedRoot)), null);

        WikidataDynamicObject saved = converted.memberRoots().get(0);
        WikidataDynamicObject editedConversion = converted.allObjects().stream()
                .filter(o -> "All.People".equals(o.getIdentifier()))
                .findFirst().orElseThrow();
        assertTrue(saved.get("primaryGroup") == editedConversion,
                "a group under another field name is rebound too");
        WikidataDynamicObject savedNested = (WikidataDynamicObject) saved.get("colleague");
        assertTrue(savedNested != nested, "a nested member is copied, not shared");
        assertTrue(((java.util.Map<?, ?>) savedNested.get("groups")).get("All.People")
                        == editedConversion,
                "a nested member's group reference is rebound at depth");
    }

    private static WikidataDynamicObject loadedGroup(String id, String name) {
        WikidataDynamicObject group = new WikidataDynamicObject(id, name);
        group.type("ViewableGroup");
        group.typeKey("ViewableGroup");
        return group;
    }

    private static WikidataDynamicObject loadedMember(String id) {
        WikidataDynamicObject member = new WikidataDynamicObject(id, id);
        member.type("GroupedEntity");
        member.typeKey("GroupedEntity");
        return member;
    }

    @Test void equalGroupPathsInDifferentMemberTypeTreesRemainDistinct() {
        quiz.transform.EditableGroup first = new quiz.transform.EditableGroup("All");
        quiz.transform.EditableGroup second = new quiz.transform.EditableGroup("All");

        var converted = ViewableToWdo.convertDomain(java.util.List.of(), java.util.List.of(
                new objectview.viewconfig.DomainGroupRoot("First", first),
                new objectview.viewconfig.DomainGroupRoot("Second", second)), null);

        WikidataDynamicObject firstRoot = converted.groupRootBindings().get(0).root();
        WikidataDynamicObject secondRoot = converted.groupRootBindings().get(1).root();
        assertTrue(firstRoot != secondRoot,
                "member-type roots with the same display path are different groups");
        assertFalse(firstRoot.typeKey().equals(secondRoot.typeKey()),
                "their persisted identity types must remain distinct too");
    }

    @Test void anUnboundMemberKeepsItsGroupWhenThePathExistsInTwoBoundTrees() {
        quiz.transform.EditableGroup first = new quiz.transform.EditableGroup("All");
        quiz.transform.EditableGroup second = new quiz.transform.EditableGroup("All");
        WikidataDynamicObject oldGroup = loadedGroup("All", "All");
        WikidataDynamicObject unbound = new WikidataDynamicObject("u1", "u1");
        unbound.type("Unbound");
        unbound.typeKey("Unbound");
        unbound.put("groups", new java.util.LinkedHashMap<>(
                java.util.Map.of("All", oldGroup)));

        var converted = ViewableToWdo.convertDomain(List.of(unbound), List.of(
                new objectview.viewconfig.DomainGroupRoot("First", first),
                new objectview.viewconfig.DomainGroupRoot("Second", second)), null);

        WikidataDynamicObject saved = converted.memberRoots().get(0);
        assertTrue(((java.util.Map<?, ?>) saved.get("groups")).get("All") == oldGroup,
                "an unbound member must retain the reference when no bound tree can be chosen");
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
                "a loaded WDO carries its persisted annotation schema");
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
