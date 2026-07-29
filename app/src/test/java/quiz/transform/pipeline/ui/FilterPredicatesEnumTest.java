package quiz.transform.pipeline.ui;

import org.junit.jupiter.api.Test;
import objectview.ViewableAdapter;
import quiz.transform.ui.DomainField;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterPredicatesEnumTest {

    /** A label-bearing domain enum (like NobelPrize.Domain) whose toString is the value
     *  the value picker offers and the snapshot flattens to. */
    enum Flag {
        PHYSICS("physics"), PEACE("peace");
        private final String label;
        Flag(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    @SuppressWarnings("unused")
    static class Row extends ViewableAdapter {
        private final Flag domain;
        Row(Flag f) { this.domain = f; }
        @Override public String getIdentifier() { return "r"; }
        @Override public String getDisplayName() { return "r"; }
    }

    private static boolean matchesPhysics(Flag value) {
        DomainField f = new DomainField("Row", "domain", false, false);
        FilterCondition c = new FilterCondition(f, FilterOperator.EQUALS, "physics", null);
        return FilterPredicates.matches(new Row(value), c);
    }

    @Test
    void liveEnumFieldMatchesItsDisplayString() {
        // A live enum value compares by toString, so EQUALS "physics" — what the value
        // picker offers — selects the matching rows and rejects the others.
        assertTrue(matchesPhysics(Flag.PHYSICS));
        assertFalse(matchesPhysics(Flag.PEACE));
    }
}
