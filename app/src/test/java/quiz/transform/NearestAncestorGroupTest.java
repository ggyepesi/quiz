package quiz.transform;

import objectview.Viewable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.transform.app.DynamicViewableGroup;
import quiz.transform.app.SnapshotDomain;
import quiz.transform.app.ViewableToWdo;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class NearestAncestorGroupTest {

    @Test void assignsNearestAnchorAndKeepsAmbiguousAndUnmatchedMembersVisible() {
        DynamicViewable position = position("position", "Position");
        DynamicViewable king = position("king", "King");
        DynamicViewable writer = position("writer", "Writer");
        king.put("superClasses", List.of(position));
        writer.put("superClasses", List.of(position));

        DynamicViewable kingOfFrance = position("king-fr", "King of France");
        kingOfFrance.put("superClasses", List.of(king));
        DynamicViewable poet = position("poet", "Court poet");
        poet.put("superClasses", List.of(writer));
        DynamicViewable hybrid = position("hybrid", "Poet king");
        hybrid.put("superClasses", List.of(king, writer));
        DynamicViewable orphan = position("orphan", "Unconnected office");

        NearestAncestorGroup group = new NearestAncestorGroup(
                "Semantic positions", "Position", "superClasses",
                List.of(king, writer));
        group.reproduce(List.of(king, kingOfFrance, poet, hybrid, orphan));

        assertEquals(List.of("King", "King of France"), names(group.getChild("King")));
        assertEquals(List.of("Court poet"), names(group.getChild("Writer")));
        assertEquals(List.of("Poet king"), names(group.getChild("Review")));
        assertEquals(List.of("Unconnected office"), names(group.getChild("Unclassified")));
        assertSame(king, group.getChild("King").getKeyRef());
        assertEquals(5, group.getMembers().size(),
                "classification never silently drops unmatched or ambiguous positions");
    }

    @Test void traversalIsCycleSafeAndStopsAtTheNearestSelectedLayer() {
        DynamicViewable broad = position("broad", "Broad office");
        DynamicViewable near = position("near", "Monarch");
        DynamicViewable concrete = position("concrete", "King of somewhere");
        concrete.put("superClasses", List.of(near));
        near.put("superClasses", List.of(broad));
        broad.put("superClasses", List.of(near));

        NearestAncestorGroup group = new NearestAncestorGroup(
                "Positions", "Position", "superClasses", List.of(near, broad));
        group.reproduce(List.of(concrete));

        assertEquals(List.of("King of somewhere"), names(group.getChild("Monarch")));
        assertEquals(List.of(), names(group.getChild("Broad office")),
                "the broader selected anchor remains visible but receives no member");
    }

    @Test void selectedAnchorKeepsAnEmptyGroupWhenTheParentScopeDoesNotReachIt() {
        DynamicViewable king = position("king", "King");
        DynamicViewable writer = position("writer", "Writer");
        DynamicViewable poet = position("poet", "Poet");
        poet.put("superClasses", List.of(writer));

        NearestAncestorGroup group = new NearestAncestorGroup(
                "Positions", "Position", "superClasses", List.of(king));
        group.reproduce(List.of(poet));

        assertEquals(List.of(), names(group.getChild("King")),
                "a persisted anchor remains visible even when it catches no members");
        assertEquals(List.of("Poet"), names(group.getChild("Unclassified")));
    }

    @Test void producerRuleRoundTripsWithEntityAnchors() {
        DynamicViewable king = position("Q12097", "King");
        NearestAncestorGroup original = new NearestAncestorGroup(
                "Positions", "Position", "superClasses", List.of(king));

        EditableGroup restored = EditableGroup.copyOf(original);

        NearestAncestorGroup nearest = assertInstanceOf(
                NearestAncestorGroup.class, restored);
        assertEquals("superClasses", nearest.field());
        assertEquals(List.of(king), nearest.anchors());
    }

    @Test void anchorReferencesSurviveSnapshotPersistence(@TempDir Path dir)
            throws Exception {
        WikidataDynamicObject king = entity("Q12097", "King");
        WikidataDynamicObject concrete = entity("Q1", "King of somewhere");
        concrete.put("superClasses", List.of(king));
        SnapshotDomain domain = new SnapshotDomain(List.of(concrete, king));
        quiz.transform.ui.TransformController controller =
                new quiz.transform.ui.TransformController(domain, null);
        EditableGroup root = (EditableGroup) controller.groupRoot("Position");
        NearestAncestorGroup group = new NearestAncestorGroup(
                "By office", "Position", "superClasses", List.of(king));
        group.reproduce(root.getMembers());
        root.addGroup(group);

        var converted = ViewableToWdo.convertDomain(
                domain.memberRoots(),
                List.of(new objectview.viewconfig.DomainGroupRoot("Position", root)), domain);
        var bindings = converted.groupRootBindings().stream()
                .map(binding -> new WikidataDynamicObjectJsonStore.GroupRootBinding(
                        binding.memberType(), binding.root())).toList();
        java.io.File file = dir.resolve("positions.snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.saveWithGroupRootBindings(converted.memberRoots(), bindings, file, domain);
        var loaded = store.loadAllWithFieldGraph(file);

        objectview.group.ViewableGroup<?> loadedRoot = DynamicViewableGroup.adapt(
                loaded.groupRootBindings().getFirst().root());
        EditableGroup restored = EditableGroup.copyOf(loadedRoot,
                new SnapshotDomain(loaded.objects(), loaded.fieldGraph()));
        NearestAncestorGroup restoredGroup = assertInstanceOf(
                NearestAncestorGroup.class, restored.getChildren().iterator().next());
        assertEquals(List.of("Q12097"), restoredGroup.anchors().stream()
                .map(Viewable::getIdentifier).toList());
        restoredGroup.reproduce(restored.getMembers());
        assertEquals(List.of("Q1", "Q12097"),
                restoredGroup.getChild("King").getMembers().stream()
                        .map(Viewable::getIdentifier).toList());
    }

    private static DynamicViewable position(String id, String name) {
        DynamicViewable value = new DynamicViewable(id, name);
        value.type("Position");
        return value;
    }

    private static WikidataDynamicObject entity(String id, String name) {
        WikidataDynamicObject value = new WikidataDynamicObject(id, name);
        value.type("Position");
        return value;
    }

    private static List<String> names(objectview.group.ViewableGroup<?> group) {
        return group.getMembers().stream().map(Viewable::getDisplayName).toList();
    }
}
