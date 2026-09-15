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
}
