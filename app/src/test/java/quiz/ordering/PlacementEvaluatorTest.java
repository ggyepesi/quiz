package quiz.ordering;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacementEvaluatorTest {
    private static OrderValue year(int year) {
        return OrderValue.parse(OrderValueType.DATE, Integer.toString(year));
    }

    @Test
    void findsFirstMiddleAndLastAscendingSlots() {
        List<OrderValue> board = List.of(year(1800), year(1900));

        assertEquals(List.of(0), PlacementEvaluator.validSlots(
                board, year(1700), SortDirection.ASCENDING, EqualValuePolicy.EQUIVALENT));
        assertEquals(List.of(1), PlacementEvaluator.validSlots(
                board, year(1850), SortDirection.ASCENDING, EqualValuePolicy.EQUIVALENT));
        assertEquals(List.of(2), PlacementEvaluator.validSlots(
                board, year(2000), SortDirection.ASCENDING, EqualValuePolicy.EQUIVALENT));
    }

    @Test
    void acceptsEveryGapInsideAnEqualValueBlock() {
        List<OrderValue> board = List.of(year(1800), year(1800), year(1900));

        assertEquals(List.of(0, 1, 2), PlacementEvaluator.validSlots(
                board, year(1800), SortDirection.ASCENDING, EqualValuePolicy.EQUIVALENT));
        assertTrue(PlacementEvaluator.evaluate(
                board, year(1800), 1, SortDirection.ASCENDING,
                EqualValuePolicy.EQUIVALENT).correct());
    }

    @Test
    void supportsDescendingBoards() {
        List<OrderValue> board = List.of(year(2000), year(1800));
        PlacementResult result = PlacementEvaluator.evaluate(
                board, year(1900), 1, SortDirection.DESCENDING,
                EqualValuePolicy.EQUIVALENT);

        assertTrue(result.correct());
        assertFalse(PlacementEvaluator.evaluate(
                board, year(1900), 0, SortDirection.DESCENDING,
                EqualValuePolicy.EQUIVALENT).correct());
    }

    @Test
    void stableTiePolicyCannotSilentlyGuessWithoutIds() {
        assertThrows(IllegalArgumentException.class, () ->
                PlacementEvaluator.validSlots(
                        List.of(year(1800)), year(1800),
                        SortDirection.ASCENDING, EqualValuePolicy.STABLE_BY_ID));
    }

    @Test
    void rejectsABoardThatIsNotInTheDeclaredOrder() {
        assertThrows(IllegalArgumentException.class, () ->
                PlacementEvaluator.validSlots(
                        List.of(year(1900), year(1800)), year(1850),
                        SortDirection.ASCENDING, EqualValuePolicy.EQUIVALENT));
    }
}
