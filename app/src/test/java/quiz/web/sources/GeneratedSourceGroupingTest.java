package quiz.web.sources;

import flag.State;
import objectview.ViewableAdapter;
import objectview.annotations.Reference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.group.ViewableGroup;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import domain.DomainModel;
import domain.DomainField;
import domain.DomainSchemas;

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
                new ReflectionDomain(List.of(item), List.of(
                        new objectview.viewconfig.DomainGroupRoot(
                                "GroupedThing", root)));
        var converted = ViewableToWdo.convertDomain(
                domain.memberRoots(), domain.groupRootBindings(), domain);
        File snapshot = dir.resolve("explicit-group-root.snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store =
                new WikidataDynamicObjectJsonStore();
        store.saveWithGroupRootBindings(
                converted.memberRoots(), converted.groupRootBindings().stream()
                        .map(binding -> new WikidataDynamicObjectJsonStore.GroupRootBinding(
                                binding.memberType(), binding.root())).toList(),
                snapshot, domain);

        var loaded = store.loadAllWithFieldGraph(snapshot);
        assertEquals(List.of("One"), loaded.memberRoots().stream()
                .map(WikidataDynamicObject::getIdentifier).toList());
        assertEquals(List.of("Root"), loaded.groupRoots().stream()
                .map(WikidataDynamicObject::getIdentifier).toList());
        assertEquals(List.of("GroupedThing"), loaded.groupRootBindings().stream()
                .map(WikidataDynamicObjectJsonStore.LoadedGroupRoot::memberType).toList());

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
                new ReflectionDomain(List.of(austria), List.of(
                        new objectview.viewconfig.DomainGroupRoot("State", root)));
        var converted = ViewableToWdo.convertDomain(
                domain.memberRoots(), domain.groupRootBindings(), domain);

        File snapshot = dir.resolve("generic-groups.snapshot.json").toFile();
        new WikidataDynamicObjectJsonStore().saveWithGroupRootBindings(
                converted.memberRoots(), converted.groupRootBindings().stream()
                        .map(binding -> new WikidataDynamicObjectJsonStore.GroupRootBinding(
                                binding.memberType(), binding.root())).toList(),
                snapshot, domain);

        GeneratedSource source = new GeneratedSource("State", snapshot);
        objectview.group.ViewableGroup<?> loadedRoot = source.rootGroup();
        assertNotNull(loadedRoot);
        assertNotNull(loadedRoot.getChild("Capitals")
                .getChild("VI").getChild("Vienna"));
        assertTrue(source.coverage().stream()
                .anyMatch(field -> "groups".equals(field.path())));
    }

    @Test
    void groupRootRequiresAnExplicitMemberType(@TempDir Path dir) throws Exception {
        State austria = new State("Austria");
        ViewableGroup root = new ViewableGroup("All");
        ViewableGroup vienna = root.getOrCreateChild("Capitals").getOrCreateChild("Vienna");
        vienna.addMember(austria);
        austria.addGroup(vienna);

        ReflectionDomain domain =
                new ReflectionDomain(List.of(austria), List.of(
                        new objectview.viewconfig.DomainGroupRoot("State", root)));
        var converted = ViewableToWdo.convertDomain(
                domain.memberRoots(), domain.groupRootBindings(), domain);
        File snapshot = dir.resolve("untyped-group-root.snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        assertThrows(IllegalArgumentException.class,
                () -> store.saveWithGroupRootBindings(converted.memberRoots(),
                        List.of(new WikidataDynamicObjectJsonStore.GroupRootBinding(
                                null, converted.groupRootBindings().getFirst().root())),
                        snapshot, domain));
    }

    @Test
    void producedGroupRulesSurviveSnapshotRoundTrip(@TempDir Path dir) throws Exception {
        quiz.transform.DynamicViewable paris =
                new quiz.transform.DynamicViewable("Paris", "Paris");
        paris.type("City");
        paris.put("region", "Europe");
        quiz.transform.DynamicViewable tokyo =
                new quiz.transform.DynamicViewable("Tokyo", "Tokyo");
        tokyo.type("City");
        tokyo.put("region", "Asia");
        DomainModel base = new DomainModel() {
            @Override public List<String> types() { return List.of("City"); }
            @Override public objectview.field.FieldSchema fieldSchema(String type) {
                return DomainSchemas.flatSchema(List.of(
                        new DomainField(
                                "City", "region", false, false)));
            }
            @Override public java.util.Collection<? extends objectview.Viewable> instances() {
                return List.of(paris, tokyo);
            }
            @Override public Class<? extends objectview.Viewable> universe() {
                return objectview.Viewable.class;
            }
        };
        quiz.transform.ui.TransformController controller =
                new quiz.transform.ui.TransformController(base, null);
        quiz.transform.EditableGroup root =
                (quiz.transform.EditableGroup) controller.groupRoot("City");
        controller.addFacetGroup("City", root, "Regions",
                new DomainField("City", "region", false, false));

        var converted = ViewableToWdo.convertDomain(
                controller.domain().memberRoots(),
                controller.domain().groupRootBindings(), controller.domain());
        File snapshot = dir.resolve("produced-groups.snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.saveWithGroupRootBindings(
                converted.memberRoots(), converted.groupRootBindings().stream()
                        .map(binding -> new WikidataDynamicObjectJsonStore.GroupRootBinding(
                                binding.memberType(), binding.root())).toList(),
                snapshot, controller.domain());

        var loaded = store.loadAllWithFieldGraph(snapshot);
        objectview.group.ViewableGroup<?> adapted =
                quiz.transform.app.DynamicViewableGroup.adapt(
                        loaded.groupRootBindings().get(0).root());
        assertEquals("facet", adapted.getChildren().iterator().next()
                .fields().read("producer"));
        quiz.transform.EditableGroup restored = quiz.transform.EditableGroup.copyOf(adapted);
        assertInstanceOf(quiz.transform.FacetGroup.class,
                restored.getChildren().iterator().next());
        quiz.transform.FacetGroup facet =
                (quiz.transform.FacetGroup) restored.getChildren().iterator().next();
        assertEquals("region", facet.field());
        assertEquals("Regions", facet.name());
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
