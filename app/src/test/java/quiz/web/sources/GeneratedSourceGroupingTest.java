package quiz.web.sources;

import flag.State;
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

        ReflectionDomain domain = new ReflectionDomain(List.of(austria));
        List<WikidataDynamicObject> converted =
                ViewableToWdo.pool(List.of(austria), domain);
        WikidataDynamicObject convertedVienna =
                ((List<?>) converted.get(0).get("groups")).stream()
                        .map(WikidataDynamicObject.class::cast)
                        .findFirst().orElseThrow();
        assertTrue(!convertedVienna.isStructuralObject());
        assertTrue(convertedVienna.structuralPath().isEmpty());

        File snapshot = dir.resolve("generic-groups.snapshot.json").toFile();
        new WikidataDynamicObjectJsonStore().saveWithFieldGraph(
                converted, snapshot, domain);

        GeneratedSource source = new GeneratedSource("State", snapshot);
        quiz.ViewableGroup loadedRoot = source.rootGroup();
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

    @Test
    void explicitManualGroupPathsRebuildTheirFullHierarchy(@TempDir Path dir)
            throws Exception {
        WikidataDynamicObject unitedStates = state("United States", 1, "dollar");
        unitedStates.put("groups", List.of(
                group("United States",
                        "All", "Currencies", "DO", "dollar", "United States"),
                group("United States",
                        "All", "Territories", "North America", "United States"),
                group("St. George's",
                        "All", "Capitals", "ST", "St. George's")));
        WikidataDynamicObject zimbabwe = state("Zimbabwe", 1, "dollar");
        zimbabwe.put("groups",
                List.of(legacyGroup("United States",
                        "All", "Currencies", "DO", "dollar", "United States")));

        File snapshot = dir.resolve("states.snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store =
                new WikidataDynamicObjectJsonStore();
        store.save(List.of(unitedStates, zimbabwe), snapshot);

        List<WikidataDynamicObject> reloaded = store.loadAll(snapshot);
        WikidataDynamicObject reloadedUnitedStates = reloaded.stream()
                .filter(object -> "United States".equals(object.qid()))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<WikidataDynamicObject> reloadedGroups =
                (List<WikidataDynamicObject>) reloadedUnitedStates.get("groups");
        assertTrue(reloadedGroups.stream().allMatch(
                        group -> group.get("path") == null
                                && group.isStructuralObject()
                                && !group.structuralPath().isEmpty()),
                "new and legacy group paths become hidden structural metadata");
        assertTrue(reloadedGroups.stream()
                        .map(WikidataDynamicObject::getReferenceLabel)
                        .anyMatch("All/Capitals/ST/St. George's"::equals),
                "a migrated group reference must show its full ancestry");
        WikidataDynamicObject capital = reloadedGroups.stream()
                .filter(group -> "All/Capitals/ST/St. George's".equals(
                        group.getReferenceLabel()))
                .findFirst().orElseThrow();
        assertEquals(Set.of("United States"), memberIds(capital),
                "the directly owning State must be a leaf member");
        assertEquals(Set.of("United States"),
                memberIds((WikidataDynamicObject) capital.get("parent")),
                "membership must propagate to group ancestors");

        WikidataDynamicObject reloadedZimbabwe =
                reloaded.stream()
                        .filter(object -> "Zimbabwe".equals(object.qid()))
                        .findFirst().orElseThrow();
        WikidataDynamicObject usDollar = reloadedGroups.stream()
                .filter(group -> "All/Currencies/DO/dollar/United States"
                        .equals(group.getReferenceLabel()))
                .findFirst().orElseThrow();
        WikidataDynamicObject zimbabweDollar =
                structuralGroupValues(reloadedZimbabwe).stream()
                        .filter(group -> "All/Currencies/DO/dollar/United States"
                                .equals(group.getReferenceLabel()))
                        .findFirst().orElseThrow();
        assertSame(usDollar, zimbabweDollar,
                "same full path must resolve to one shared group object");
        assertEquals(Set.of("United States", "Zimbabwe"),
                memberIds(usDollar),
                "a shared group must contain every owning State");

        GeneratedSource source = new GeneratedSource("State", snapshot);
        quiz.ViewableGroup root = source.rootGroup();

        assertNotNull(root);
        assertNull(root.getChild("United States"),
                "a leaf label must not be promoted to the root");
        quiz.ViewableGroup dollar = root.getChild("Currencies")
                .getChild("DO")
                .getChild("dollar");
        quiz.ViewableGroup currencyLeaf = dollar.getChild("United States");
        assertNotNull(currencyLeaf);
        assertEquals(
                Set.of("United States", "Zimbabwe"),
                currencyLeaf.getMembers().stream()
                        .map(objectview.Viewable::getIdentifier)
                        .collect(Collectors.toSet()));

        quiz.ViewableGroup territoryLeaf = root.getChild("Territories")
                .getChild("North America")
                .getChild("United States");
        assertNotNull(territoryLeaf,
                "same-named leaves in different branches remain distinct");
        assertNotNull(root.getChild("Capitals").getChild("ST")
                        .getChild("St. George's"),
                "punctuation in a group label must survive snapshot reconstruction");

        Set<String> coveredPaths = source.coverage().stream()
                .map(Coverage.FieldCoverage::path)
                .collect(Collectors.toSet());
        assertTrue(coveredPaths.contains("groups"),
                "group membership is an ordinary configurable reference field");
    }

    private static WikidataDynamicObject state(
            String id, int version, String currency) {
        WikidataDynamicObject state = new WikidataDynamicObject(id, id);
        state.type("State");
        state.put("version", version);
        state.put("currency", currency);
        return state;
    }

    private static WikidataDynamicObject group(
            String name, String... path) {
        WikidataDynamicObject group =
                new WikidataDynamicObject(null, name);
        group.type("ViewableGroup");
        group.valueObject(true);
        group.structuralPath(List.of(path));
        return group;
    }

    private static WikidataDynamicObject legacyGroup(
            String name, String... path) {
        WikidataDynamicObject group =
                new WikidataDynamicObject(null, name);
        group.type("ViewableGroup");
        group.valueObject(true);
        group.put("path", List.of(path));
        return group;
    }

    @SuppressWarnings("unchecked")
    private static List<WikidataDynamicObject> structuralGroupValues(
            WikidataDynamicObject owner) {
        return (List<WikidataDynamicObject>) owner.get("groups");
    }

    private static Set<String> memberIds(
            WikidataDynamicObject group) {
        return ((List<?>) group.get("members")).stream()
                .map(WikidataDynamicObject.class::cast)
                .map(WikidataDynamicObject::qid)
                .collect(Collectors.toSet());
    }
}
