package quiz.ordering;

import java.util.List;

public record PlacementResult(boolean correct, List<Integer> validSlots) {
    public PlacementResult {
        validSlots = List.copyOf(validSlots);
    }
}
