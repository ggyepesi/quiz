package quiz.transform.pipeline.ui;

import org.junit.jupiter.api.Test;
import quiz.QuizableAdapter;
import quiz.transform.ui.DomainField;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterPredicatesEnumTest {

    /** Mirrors State.FlagStatus: a label-bearing enum whose toString is the value. */
    enum Flag {
        AVAILABLE(""), MISSING("no flag");
        private final String label;
        Flag(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    @SuppressWarnings("unused")
    static class Row extends QuizableAdapter {
        private final Flag flagStatus;
        Row(Flag f) { this.flagStatus = f; }
        @Override public String getIdentifier() { return "r"; }
        @Override public String getDisplayName() { return "r"; }
    }

    private static boolean matchesNoFlag(Flag value) {
        DomainField f = new DomainField("Row", "flagStatus", false, false);
        FilterCondition c = new FilterCondition(f, FilterOperator.EQUALS, "no flag", null);
        return FilterPredicates.matches(new Row(value), c);
    }

    @Test
    void liveEnumFieldMatchesItsDisplayString() {
        // A live enum value compares by toString, so EQUALS "no flag" — what the value
        // picker offers — selects the flagless rows and rejects the flagged ones.
        assertTrue(matchesNoFlag(Flag.MISSING));
        assertFalse(matchesNoFlag(Flag.AVAILABLE));
    }
}
