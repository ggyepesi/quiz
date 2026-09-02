package process.swing.workflow;

import objectview.Viewable;
import objectview.ViewableAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whether a results tab can be applied from at all.
 *
 * <p>The graph frontier shows two tabs per pattern: the nodes already expanded, and the
 * frontier awaiting a decision. Only the second carries decisions, so selecting on the
 * first moved nothing while still offering Select all and a greyed Expand — an offer the
 * workflow cannot keep, and one a reader reasonably reads as "not yet" rather than
 * "never here".
 */
class ResultTabActionabilityTest {

    private static final class Row extends ViewableAdapter {
        private final String id;
        private Row(String id) { this.id = id; }
        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return id; }
        @Override public String typeName() { return "Row"; }
    }

    private static ProcessWorkflowResults.Card<String> card(String id, String decision) {
        return new ProcessWorkflowResults.Card<>(new Row(id), () -> decision, false);
    }

    private static boolean actionable(ProcessWorkflowResults.Tab<String> tab) {
        return tab.cards().stream().anyMatch(c -> c.decision().get() != null);
    }

    @Test void aTabWhoseCardsDecideNothingIsNotActionable() {
        ProcessWorkflowResults.Tab<String> expanded = new ProcessWorkflowResults.Tab<>(
                "Position expanded", List.of(card("Q1", null), card("Q2", null)));

        assertFalse(actionable(expanded),
                "every card answers null, so applying from this tab cannot move anything");
    }

    @Test void aTabWithDecisionsIsActionable() {
        ProcessWorkflowResults.Tab<String> frontier = new ProcessWorkflowResults.Tab<>(
                "Position frontier", List.of(card("Q3", "expand-Q3")));

        assertTrue(actionable(frontier));
    }

    /** One card that decides is enough; the rest may be context. */
    @Test void oneDecidingCardMakesTheTabActionable() {
        ProcessWorkflowResults.Tab<String> mixed = new ProcessWorkflowResults.Tab<>(
                "Mixed", List.of(card("Q1", null), card("Q2", "expand-Q2")));

        assertTrue(actionable(mixed));
    }

    @Test void anEmptyTabIsNotActionable() {
        assertFalse(actionable(new ProcessWorkflowResults.Tab<>("Empty", List.of())));
    }

    /**
     * The decision is read at apply time, so a card that decides nothing NOW may decide
     * later — actionability is asked when the tab is shown, not cached at construction.
     */
    @Test void theDecisionIsAskedNotRemembered() {
        String[] answer = {null};
        ProcessWorkflowResults.Tab<String> tab = new ProcessWorkflowResults.Tab<>("Late",
                List.of(new ProcessWorkflowResults.Card<>(
                        new Row("Q9"), () -> answer[0], false)));

        assertFalse(actionable(tab));
        answer[0] = "now-it-decides";
        assertTrue(actionable(tab));
    }
}
