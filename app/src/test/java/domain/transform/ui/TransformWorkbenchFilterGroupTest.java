package domain.transform.ui;

import domain.DomainField;
import flag.State;
import objectview.Viewable;
import org.junit.jupiter.api.Test;
import domain.transform.OperationGroup;
import domain.transform.FilterCondition;
import domain.transform.FilterOperator;

import javax.swing.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TransformWorkbenchFilterGroupTest {
    @Test void relationClosureSeedChooserLeavesRoomForSearchHitsAndCards() {
        assertTrue(TransformWorkbenchPanel.relationClosureSeedDialogSize().width >= 900);
        assertTrue(TransformWorkbenchPanel.relationClosureSeedDialogSize().height >= 700);
        assertTrue(TransformWorkbenchPanel.relationClosureSeedDialogMinimumSize().height
                >= 560);
    }

    @Test void fieldsPaneCanShrinkAndDefaultLayoutFavorsTheInstanceWorkspace() {
        JPanel fields = new JPanel();
        JPanel instances = new JPanel();

        JSplitPane split = TransformWorkbenchPanel.workspaceSplit(fields, instances);

        assertEquals(JSplitPane.HORIZONTAL_SPLIT, split.getOrientation());
        assertEquals(280, fields.getMinimumSize().width);
        assertEquals(420, instances.getMinimumSize().width);
        assertEquals(420, split.getDividerLocation());
        assertTrue(split.getResizeWeight() < 0.5,
                "additional window width should primarily belong to the instance workspace");
        assertTrue(split.isContinuousLayout());
        assertTrue(split.isOneTouchExpandable());
    }

    @Test void instanceActionsWrapWithoutClippingAnalyzeRelations() {
        assertTrue(TransformWorkbenchPanel.instanceActionsLayout()
                        instanceof objectview.utils.swing.WrapLayout,
                "the default-width action row must report the height of wrapped buttons");
    }

    @Test void discoveryOpensForTheVisibleClassBeforeAnyCardsAreSelected() {
        var maat = new domain.runtime.WikidataDynamicObject("Q170471", "Maat");
        maat.type("EgyptianDeity");
        maat.enwikiTitle("Maat");

        assertEquals("Choose one logical class before opening Discovery",
                TransformWorkbenchPanel.discoveryUnavailable(
                        TransformController.ALL_ENTITIES, List.of(maat)));
        assertEquals("No currently shown instance has a Wikidata QID",
                TransformWorkbenchPanel.discoveryUnavailable(
                        "State", List.of(new State("France"))));
        assertNull(TransformWorkbenchPanel.discoveryUnavailable(
                "EgyptianDeity", List.of(maat)),
                "Add selection belongs to the Discovery workbench, so opening it must not "
                        + "require a prior card selection");
    }

    @Test void relationEquivalenceClassesUseTheSameGroupsAsTheMainWorkbench() {
        domain.transform.DynamicViewable connectedOne = value("one", "One");
        domain.transform.DynamicViewable connectedTwo = value("two", "Two");
        domain.transform.DynamicViewable singleton = value("single", "Single");
        connectedOne.put("next", List.of(connectedTwo));
        domain.transform.RelationProfile profile = domain.transform.RelationProfile.of(
                List.of(connectedOne, connectedTwo, singleton), "next", "");

        domain.transform.EditableGroup groups =
                TransformWorkbenchPanel.relationEquivalenceClassGroups(profile);

        assertEquals(2, groups.getChildren().size(),
                "the connected pair and the singleton are both displayed classes");
        assertEquals(List.of(2, 1), groups.getChildren().stream()
                .map(group -> group.getMembers().size()).toList());
    }

    @Test void aRelationClosurePathUsesTheSharedObjectViewBrowser() {
        State france = new State("France");
        TransformController controller = new TransformController(
                new ReflectionDomain(List.of(france)), null);
        domain.transform.RelationClosureGroup.RelationPath path =
                new domain.transform.RelationClosureGroup.RelationPath(
                        List.of(new domain.transform.RelationClosureGroup.PathNode(
                                domain.transform.RelationClosureGroup.PathRole.ENTITY, france)),
                        List.of());
        graphview.InteractiveGraphView graph = new graphview.InteractiveGraphView();
        domain.transform.RelationClosureGroup closure = new domain.transform.RelationClosureGroup(
                "States", "State", "Relation", "member", "entity",
                "States", List.of(france));

        JTabbedPane view = assertInstanceOf(JTabbedPane.class,
                TransformWorkbenchPanel.relationClosurePathView(
                        controller, closure, path, graph));

        assertSame(graph, view.getComponentAt(0));
        assertEquals(List.of("Path", "Timeline", "Instances"),
                java.util.stream.IntStream.range(0, view.getTabCount())
                        .mapToObj(view::getTitleAt).toList());
        assertInstanceOf(objectview.view.SearchableView.class, view.getComponentAt(2),
                "path instances must still use the ordinary ObjectView browser");
        graph.close();
    }

    @Test void aMultiSeedPathHeaderNamesTheSeedThatActuallyReachedTheMember() {
        domain.transform.DynamicViewable configuredFirst = value("P0", "First configured");
        domain.transform.DynamicViewable actualStart = value("P1", "Actual start");
        domain.transform.DynamicViewable target = value("H1", "Reached holder");
        target.type("Person");
        var startNode = new domain.transform.RelationClosureGroup.PathNode(
                domain.transform.RelationClosureGroup.PathRole.ENTITY, actualStart);
        var targetNode = new domain.transform.RelationClosureGroup.PathNode(
                domain.transform.RelationClosureGroup.PathRole.MEMBER, target);
        domain.transform.DynamicViewable bridge = value("O1", "Holding");
        bridge.type("OfficeHolding");
        var path = new domain.transform.RelationClosureGroup.RelationPath(
                List.of(startNode, targetNode), List.of(
                new domain.transform.RelationClosureGroup.PathEdge(
                        startNode, targetNode, bridge)));
        var closure = new domain.transform.RelationClosureGroup(
                "Holders", "Person", "OfficeHolding", "source", "position",
                "Positions", List.of(configuredFirst, actualStart));

        assertTrue(TransformWorkbenchPanel.closurePathHeader(closure, path, target)
                .startsWith("Actual start → Reached holder"));
    }

    @Test void aRelationClosurePathGraphShowsOnlyDomainEndpointsAndRelationEdges() {
        domain.transform.DynamicViewable first = value("P1", "Apostolic king");
        first.type("Position");
        domain.transform.DynamicViewable holder = value("H1", "Louis");
        holder.type("Person");
        domain.transform.DynamicViewable shared = value("P2", "Carolingian emperor");
        shared.type("Position");
        domain.transform.DynamicViewable holdingOne = value("O1", "First holding");
        holdingOne.type("OfficeHolding");
        domain.transform.DynamicViewable holdingTwo = value("O2", "Second holding");
        holdingTwo.type("OfficeHolding");
        var firstNode = new domain.transform.RelationClosureGroup.PathNode(
                domain.transform.RelationClosureGroup.PathRole.ENTITY, first);
        var holderNode = new domain.transform.RelationClosureGroup.PathNode(
                domain.transform.RelationClosureGroup.PathRole.MEMBER, holder);
        var sharedNode = new domain.transform.RelationClosureGroup.PathNode(
                domain.transform.RelationClosureGroup.PathRole.ENTITY, shared);
        var path = new domain.transform.RelationClosureGroup.RelationPath(
                List.of(firstNode, holderNode, sharedNode), List.of(
                new domain.transform.RelationClosureGroup.PathEdge(
                        firstNode, holderNode, holdingOne),
                new domain.transform.RelationClosureGroup.PathEdge(
                        holderNode, sharedNode, holdingTwo)));

        graphview.GraphViewModel graph = RelationClosurePathProjection.graph(path);

        assertEquals(List.of("Apostolic king", "Louis", "Carolingian emperor"),
                graph.nodes().stream().map(graphview.GraphViewModel.Node::label).toList());
        assertEquals(List.of("Start Position", "Member Person", "Shared Position"),
                graph.nodes().stream().map(node -> node.details().get("Path role")).toList());
        assertEquals(List.of("OfficeHolding", "OfficeHolding"), graph.edges().stream()
                .map(graphview.GraphViewModel.Edge::label).toList());
    }

    @Test void missingWikidataLabelMeansBlankOrStillShowingTheQid() {
        assertTrue(TransformWorkbenchPanel.withoutWikidataLabel(
                new domain.runtime.WikidataDynamicObject("Q1", "Q1")));
        assertFalse(TransformWorkbenchPanel.withoutWikidataLabel(
                new domain.runtime.WikidataDynamicObject("Q1", "Named")));
        assertFalse(TransformWorkbenchPanel.withoutWikidataLabel(new State("France")));
    }

    @Test void newlyAddedFilterGroupBecomesTheActiveVisibleGroup() {
        TransformWorkbenchPanel panel = new TransformWorkbenchPanel(
                new ReflectionDomain(List.of(new State("France"), new State("Germany"))));
        FilterCondition condition = new FilterCondition(
                new DomainField("State", "name", false, false),
                FilterOperator.EQUALS, "France", null);

        OperationGroup created = panel.addFilterGroup("Only France", condition);

        assertEquals("Only France", created.name());
        assertEquals("Only France", created.getDisplayName(),
                "the tree shows the short name; the condition is available on request");
        assertSame(created, panel.activeGroupForTest(),
                "the result of Add filter group must be selected and shown");
        panel.close();
    }

    private static domain.transform.DynamicViewable value(String id, String label) {
        domain.transform.DynamicViewable value = new domain.transform.DynamicViewable(id, label);
        value.type("Position");
        return value;
    }

    /** A disabled path command says what would enable it, not one fixed sentence. */
    @org.junit.jupiter.api.Test void theClosurePathCommandSaysWhyItIsUnavailable() {
        org.junit.jupiter.api.Assertions.assertEquals(
                "Select one instance of this group to show its path",
                TransformWorkbenchPanel.closurePathUnavailable(null, java.util.List.of()));
        org.junit.jupiter.api.Assertions.assertEquals(
                "Select a single instance to show its path",
                TransformWorkbenchPanel.closurePathUnavailable(null, java.util.List.of(
                        new domain.transform.DynamicViewable("Q1", "One"),
                        new domain.transform.DynamicViewable("Q2", "Two"))));
    }
}
