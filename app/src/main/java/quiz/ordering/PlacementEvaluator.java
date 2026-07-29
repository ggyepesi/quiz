package quiz.ordering;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure placement rules, independent of HTTP, rendering, and sessions.
 */
public final class PlacementEvaluator {
    private PlacementEvaluator() {}

    public static PlacementResult evaluate(
            List<OrderValue> ordered,
            OrderValue candidate,
            int proposedSlot,
            SortDirection direction,
            EqualValuePolicy equality) {
        List<Integer> valid = validSlots(ordered, candidate, direction, equality);
        return new PlacementResult(valid.contains(proposedSlot), valid);
    }

    public static List<Integer> validSlots(
            List<OrderValue> ordered,
            OrderValue candidate,
            SortDirection direction,
            EqualValuePolicy equality) {
        if (ordered == null || candidate == null) {
            throw new IllegalArgumentException("Ordered values and candidate are required");
        }
        SortDirection dir = direction == null ? SortDirection.ASCENDING : direction;
        EqualValuePolicy eq = equality == null ? EqualValuePolicy.EQUIVALENT : equality;
        if (eq == EqualValuePolicy.STABLE_BY_ID) {
            throw new IllegalArgumentException(
                    "STABLE_BY_ID placement requires item identifiers");
        }
        verifyOrdered(ordered, dir);

        List<Integer> valid = new ArrayList<>();
        for (int slot = 0; slot <= ordered.size(); slot++) {
            OrderValue before = slot == 0 ? null : ordered.get(slot - 1);
            OrderValue after = slot == ordered.size() ? null : ordered.get(slot);
            boolean afterBefore = before == null || dir.apply(candidate.compareTo(before)) >= 0;
            boolean beforeAfter = after == null || dir.apply(candidate.compareTo(after)) <= 0;
            if (afterBefore && beforeAfter) {
                valid.add(slot);
            }
        }

        return List.copyOf(valid);
    }

    private static void verifyOrdered(List<OrderValue> values, SortDirection direction) {
        for (int i = 1; i < values.size(); i++) {
            if (direction.apply(values.get(i - 1).compareTo(values.get(i))) > 0) {
                throw new IllegalArgumentException("Board values are not ordered");
            }
        }
    }
}
