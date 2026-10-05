package quiz.transform.ui;

import domain.DomainField;
import flag.State;
import org.junit.jupiter.api.Test;
import quiz.transform.OperationGroup;
import quiz.transform.pipeline.ui.FilterCondition;
import quiz.transform.pipeline.ui.FilterOperator;

import javax.swing.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test void relationEquivalenceClassesUseTheSameGroupsAsTheMainWorkbench() {
        quiz.transform.DynamicViewable connectedOne = value("one", "One");
        quiz.transform.DynamicViewable connectedTwo = value("two", "Two");
        quiz.transform.DynamicViewable singleton = value("single", "Single");
        connectedOne.put("next", List.of(connectedTwo));
        quiz.transform.RelationProfile profile = quiz.transform.RelationProfile.of(
                List.of(connectedOne, connectedTwo, singleton), "next", "");

        quiz.transform.EditableGroup groups =
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
        quiz.transform.RelationClosureGroup.RelationPath path =
                new quiz.transform.RelationClosureGroup.RelationPath(
                        List.of(new quiz.transform.RelationClosureGroup.PathNode(
                                quiz.transform.RelationClosureGroup.PathRole.ENTITY, france)),
                        List.of());
        graphview.InteractiveGraphView graph = new graphview.InteractiveGraphView();
        quiz.transform.RelationClosureGroup closure = new quiz.transform.RelationClosureGroup(
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
        quiz.transform.DynamicViewable configuredFirst = value("P0", "First configured");
        quiz.transform.DynamicViewable actualStart = value("P1", "Actual start");
        quiz.transform.DynamicViewable target = value("H1", "Reached holder");
        target.type("Person");
        var startNode = new quiz.transform.RelationClosureGroup.PathNode(
                quiz.transform.RelationClosureGroup.PathRole.ENTITY, actualStart);
        var targetNode = new quiz.transform.RelationClosureGroup.PathNode(
                quiz.transform.RelationClosureGroup.PathRole.MEMBER, target);
        quiz.transform.DynamicViewable bridge = value("O1", "Holding");
        bridge.type("OfficeHolding");
        var path = new quiz.transform.RelationClosureGroup.RelationPath(
                List.of(startNode, targetNode), List.of(
                new quiz.transform.RelationClosureGroup.PathEdge(
                        startNode, targetNode, bridge)));
        var closure = new quiz.transform.RelationClosureGroup(
                "Holders", "Person", "OfficeHolding", "source", "position",
                "Positions", List.of(configuredFirst, actualStart));

        assertTrue(TransformWorkbenchPanel.closurePathHeader(closure, path, target)
                .startsWith("Actual start → Reached holder"));
    }

    @Test void aRelationClosurePathGraphShowsOnlyDomainEndpointsAndRelationEdges() {
        quiz.transform.DynamicViewable first = value("P1", "Apostolic king");
        first.type("Position");
        quiz.transform.DynamicViewable holder = value("H1", "Louis");
        holder.type("Person");
        quiz.transform.DynamicViewable shared = value("P2", "Carolingian emperor");
        shared.type("Position");
        quiz.transform.DynamicViewable holdingOne = value("O1", "First holding");
        holdingOne.type("OfficeHolding");
        quiz.transform.DynamicViewable holdingTwo = value("O2", "Second holding");
        holdingTwo.type("OfficeHolding");
        var firstNode = new quiz.transform.RelationClosureGroup.PathNode(
                quiz.transform.RelationClosureGroup.PathRole.ENTITY, first);
        var holderNode = new quiz.transform.RelationClosureGroup.PathNode(
                quiz.transform.RelationClosureGroup.PathRole.MEMBER, holder);
        var sharedNode = new quiz.transform.RelationClosureGroup.PathNode(
                quiz.transform.RelationClosureGroup.PathRole.ENTITY, shared);
        var path = new quiz.transform.RelationClosureGroup.RelationPath(
                List.of(firstNode, holderNode, sharedNode), List.of(
                new quiz.transform.RelationClosureGroup.PathEdge(
                        firstNode, holderNode, holdingOne),
                new quiz.transform.RelationClosureGroup.PathEdge(
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
                new wikidata.explore.extract.WikidataDynamicObject("Q1", "Q1")));
        assertFalse(TransformWorkbenchPanel.withoutWikidataLabel(
                new wikidata.explore.extract.WikidataDynamicObject("Q1", "Named")));
        assertFalse(TransformWorkbenchPanel.withoutWikidataLabel(new State("France")));
    }

    @Test void experimentSelectionMarksExistingInstanceCardsWithoutCopyingTheList() {
        State france = new State("France");
        TransformWorkbenchPanel panel = new TransformWorkbenchPanel(
                new ReflectionDomain(List.of(france)));

        assertNull(panel.experimentMark(france));
        // Reflection State has no Wikidata source, so it cannot be marked accidentally.
        panel.addExperimentInstance(france);
        assertNull(panel.experimentMark(france));

        var qid = new wikidata.explore.extract.WikidataDynamicObject("Q142", "France");
        qid.type("Country");
        panel.addExperimentInstance(qid);
        assertNotNull(panel.experimentMark(qid));
        panel.close();
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

    private static quiz.transform.DynamicViewable value(String id, String label) {
        quiz.transform.DynamicViewable value = new quiz.transform.DynamicViewable(id, label);
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
                        new quiz.transform.DynamicViewable("Q1", "One"),
                        new quiz.transform.DynamicViewable("Q2", "Two"))));
    }
}
