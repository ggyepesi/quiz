package quiz;

import objectview.FieldLabels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FieldLabelsTest {

    @Test void humanizesCamelAndSnake() {
        assertEquals("Won", FieldLabels.humanize("won"));
        assertEquals("Is Winner", FieldLabels.humanize("isWinner"));
        assertEquals("Won award", FieldLabels.humanize("won_award"));
        assertEquals("", FieldLabels.humanize(null));
    }

    @Test void booleanLabelFlagsTrueAndNegatesFalse() {
        assertEquals("Won", FieldLabels.booleanLabel(true, "won"));
        assertEquals("Not won", FieldLabels.booleanLabel(false, "won"));
    }

    @Test void booleanBucketParsesStringValues() {
        assertEquals("Won", FieldLabels.booleanBucket("true", "won"));
        assertEquals("Won", FieldLabels.booleanBucket("1", "won"));
        assertEquals("Not won", FieldLabels.booleanBucket("false", "won"));
        assertEquals(null, FieldLabels.booleanBucket("  ", "won"));
    }
}
