package quiz.round;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoundProgressTest {

    @Test
    void startsAtTheFirstRoundAndAdvancesUntilComplete() {
        RoundProgress progress = new RoundProgress(3);

        assertEquals(1, progress.currentRound());
        assertEquals(0, progress.currentIndex());
        assertFalse(progress.isLastRound());

        assertTrue(progress.advance());
        assertEquals(2, progress.currentRound());
        assertTrue(progress.advance());
        assertTrue(progress.isLastRound());

        assertFalse(progress.advance());
        assertTrue(progress.isComplete());
        assertThrows(IllegalStateException.class, progress::currentIndex);
    }

    @Test
    void zeroRoundsStartsCompleted() {
        RoundProgress progress = new RoundProgress(0);

        assertTrue(progress.isComplete());
        assertEquals(new RoundProgress.Snapshot(0, 0, true),
                progress.snapshot());
    }

    @Test
    void dynamicSequenceCanCompleteEarly() {
        RoundProgress progress = new RoundProgress(5);
        progress.advance();

        progress.complete();

        assertTrue(progress.isComplete());
        assertEquals(2, progress.currentRound());
        assertFalse(progress.advance());
    }

    @Test
    void rejectsNegativeRoundCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> new RoundProgress(-1));
    }
}
