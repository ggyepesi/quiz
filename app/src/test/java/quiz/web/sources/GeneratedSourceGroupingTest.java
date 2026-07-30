package quiz.web.sources;

import flag.State;
import objectview.ViewableAdapter;
import objectview.annotations.Reference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.ViewableGroup;
import quiz.transform.app.ViewableToWdo;
import quiz.transform.ui.ReflectionDomain;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedSourceGroupingTest {

    private static final class GroupedThing extends ViewableAdapter {
        private final String name;
        @Reference
        private final List<ViewableGroup> affiliations =
                new java.util.ArrayList<>();

        private GroupedThing(String name) {
            this.name = name;
        }

        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
    }

    @Test
    void anOrdinaryGroupReferenceDoesNotDeclareAPresentationRoot(
            @TempDir Path dir) throws Exception {
        GroupedThing item = new GroupedThing("One");
        ViewableGroup root = new ViewableGroup("Root");
        ViewableGroup leaf = root.getOrCreateChild("Leaf");
        leaf.addMember(item);
        item.affiliations.add(leaf);

        ReflectionDomain domain = new ReflectionDomain(List.of(item));
        List<WikidataDynamicObject> converted =
                ViewableToWdo.pool(List.of(item), domain);
        File snapshot = dir.resolve("renamed-group-field.snapshot.json").toFile();
        new WikidataDynamicObjectJsonStore().saveWithFieldGraph(
                converted, snapshot, domain);

        GeneratedSource source = new GeneratedSource("GroupedThing", snapshot);
        assertNull(source.rootGroup());
    }

    @Test
    void preservesAnExplicitRootWithoutAMemberBackReference(
            @TempDir Path dir) throws Exception {
        GroupedThing item = new GroupedThing("One");
        ViewableGroup root = new ViewableGroup("Root");
        ViewableGroup leaf = root.getOrCreateChild("Leaf")
                .role(objectview.group.ViewableGroup.Role.BUCKET)
                .keyRef(item);
        leaf.addMember(item);
        ReflectionDomain domain =
                new ReflectionDomain(List.of(item), List.of(root));
        var converted = ViewableToWdo.convertDomain(
                domain.memberRoots(), domain.groupRoots(), domain);
        File snapshot = dir.resolve("explicit-group-root.snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store =
                new WikidataDynamicObjectJsonStore();
        store.saveWithFieldGraph(
                converted.memberRoots(), converted.groupRoots(),
                snapshot, domain);

        var loaded = store.loadAllWithFieldGraph(snapshot);
        assertEquals(List.of("One"), loaded.memberRoots().stream()
                .map(WikidataDynamicObject::getIdentifier).toList());
        assertEquals(List.of("Root"), loaded.groupRoots().stream()
                .map(WikidataDynamicObject::getIdentifier).toList());

        GeneratedSource source = new GeneratedSource("GroupedThing", snapshot);
        objectview.group.ViewableGroup<?> loadedRoot = source.rootGroup();
        assertEquals("Root", loadedRoot.getDisplayName());
        assertEquals(objectview.group.ViewableGroup.Role.BUCKET,
                loadedRoot.getChild("Leaf").getRole());
        assertEquals("One", loadedRoot.getChild("Leaf")
                .getKeyRef().getIdentifier());
        assertSame(source.load().iterator().next(),
                loadedRoot.getChild("Leaf").getMembers().iterator().next());
    }

    @Test
    void registerAllDoesNotServeViewableGroupAsItsOwnDataset(
            @TempDir Path dir) throws Exception {
        State austria = new State("Austria");
        ViewableGroup vienna = new ViewableGroup("All")
                .getOrCreateChild("Capitals").getOrCreateChild("Vienna");
        vienna.addMember(austria);
        austria.addGroup(vienna);

        ReflectionDomain domain = new ReflectionDomain(List.of(austria));
        File snapshot = dir.resolve("state-with-groups.snapshot.json").toFile();
        new WikidataDynamicObjectJsonStore().saveWithFieldGraph(
                ViewableToWdo.pool(List.of(austria), domain), snapshot, domain);

        quiz.web.ViewableStore store = new quiz.web.ViewableStore();
        GeneratedSource.registerAll(store, "State", snapshot);

        assertTrue(store.types().contains("State"),
                "the domain type is served");
        assertFalse(store.types().contains("ViewableGroup"),
                "a group hierarchy is facet structure, not a browsable dataset —"
                        + " it reaches the web through members' groups field");
    }

    @Test
    void ordinaryViewableGroupGraphRebuildsWithoutStructuralMetadata(
            @TempDir Path dir) throws Exception {
        State austria = new State("Austria");
        ViewableGroup root = new ViewableGroup("All");
        ViewableGroup vienna = root.getOrCreateChild("Capitals")
                .getOrCreateChild("VI")
                .getOrCreateChild("Vienna");
        vienna.addMember(austria);
        austria.addGroup(vienna);

        ReflectionDomain domain =
                new ReflectionDomain(List.of(austria), List.of(root));
        var converted = ViewableToWdo.convertDomain(
                domain.memberRoots(), domain.groupRoots(), domain);

        File snapshot = dir.resolve("generic-groups.snapshot.json").toFile();
        new WikidataDynamicObjectJsonStore().saveWithFieldGraph(
                converted.memberRoots(), converted.groupRoots(), snapshot, domain);

        GeneratedSource source = new GeneratedSource("State", snapshot);
        objectview.group.ViewableGroup<?> loadedRoot = source.rootGroup();
        assertNotNull(loadedRoot);
        assertNotNull(loadedRoot.getChild("Capitals")
                .getChild("VI").getChild("Vienna"));
        assertTrue(source.coverage().stream()
                .anyMatch(field -> "groups".equals(field.path())));
    }

    @Test
    void fieldsDoNotAutomaticallyBecomeGroupingFacets(@TempDir Path dir)
            throws Exception {
        WikidataDynamicObject first = state("A", 1, "euro");
        WikidataDynamicObject second = state("B", 2, "dollar");
        File snapshot = dir.resolve("states.snapshot.json").toFile();
        new WikidataDynamicObjectJsonStore().save(List.of(first, second), snapshot);

        GeneratedSource source = new GeneratedSource("State", snapshot);

        assertTrue(source.dimensions().isEmpty(),
                "version/currency fields require an explicit Transform GROUP_BY");
        assertNull(source.rootGroup(),
                "a raw snapshot must not synthesize a group tree");

        Set<String> coveredPaths = source.coverage().stream()
                .map(Coverage.FieldCoverage::path)
                .collect(Collectors.toSet());
        assertTrue(coveredPaths.containsAll(Set.of("version", "currency")),
                "removing implicit grouping must not remove field validation");
    }

    private static WikidataDynamicObject state(
            String id, int version, String currency) {
        WikidataDynamicObject state = new WikidataDynamicObject(id, id);
        state.type("State");
        state.put("version", version);
        state.put("currency", currency);
        return state;
    }




}
