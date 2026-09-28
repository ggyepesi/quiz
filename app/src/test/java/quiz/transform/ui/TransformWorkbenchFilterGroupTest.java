package quiz.transform.ui;

import domain.DomainField;
import flag.State;
import org.junit.jupiter.api.Test;
import quiz.transform.OperationGroup;
import quiz.transform.pipeline.ui.FilterCondition;
import quiz.transform.pipeline.ui.FilterOperator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TransformWorkbenchFilterGroupTest {
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

}
