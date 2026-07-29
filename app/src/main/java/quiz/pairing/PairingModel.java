package quiz.pairing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure interaction model for a two-sided, one-to-one pairing board.
 *
 * <p>The model owns selection, pairing, reassignment, and completion rules.
 * It deliberately has no Swing, rendering, scoring, or round dependencies.
 * Left and right values act as board-choice identities, so callers that show
 * duplicate domain values should supply distinct choice tokens.
 */
public final class PairingModel<L, R> {

    public enum ChoiceState {
        IDLE,
        SELECTED,
        MATCHED
    }

    /**
     * {@code number} is a stable presentation-neutral identity for a newly
     * created match. A UI may map it to a color, symbol, or connector.
     */
    public record Match<L, R>(L left, R right, int number) {}

    private final int expectedPairs;
    private final Map<L, Match<L, R>> leftMatches = new LinkedHashMap<>();
    private final Map<R, Match<L, R>> rightMatches = new LinkedHashMap<>();

    private L selectedLeft;
    private R selectedRight;
    private int nextMatchNumber;

    public PairingModel(int expectedPairs) {
        if (expectedPairs < 0) {
            throw new IllegalArgumentException(
                    "Expected pair count cannot be negative");
        }
        this.expectedPairs = expectedPairs;
    }

    public void chooseLeft(L left) {
        Objects.requireNonNull(left, "Left choice is required");
        if (isSolved()) {
            return;
        }

        if (Objects.equals(selectedLeft, left)) {
            selectedLeft = null;
            return;
        }

        if (selectedRight != null) {
            R right = selectedRight;
            selectedRight = null;
            pair(left, right);
            return;
        }

        selectedLeft = null;
        unpairLeft(left);
        selectedLeft = left;
    }

    public void chooseRight(R right) {
        Objects.requireNonNull(right, "Right choice is required");
        if (isSolved()) {
            return;
        }

        if (Objects.equals(selectedRight, right)) {
            selectedRight = null;
            return;
        }

        if (selectedLeft != null) {
            L left = selectedLeft;
            selectedLeft = null;
            pair(left, right);
            return;
        }

        selectedRight = null;
        unpairRight(right);
        selectedRight = right;
    }

    public int expectedPairs() {
        return expectedPairs;
    }

    public int matchedPairs() {
        return leftMatches.size();
    }

    public boolean isSolved() {
        return expectedPairs > 0 && matchedPairs() == expectedPairs;
    }

    public ChoiceState leftState(L left) {
        if (Objects.equals(selectedLeft, left)) {
            return ChoiceState.SELECTED;
        }
        return leftMatches.containsKey(left)
                ? ChoiceState.MATCHED : ChoiceState.IDLE;
    }

    public ChoiceState rightState(R right) {
        if (Objects.equals(selectedRight, right)) {
            return ChoiceState.SELECTED;
        }
        return rightMatches.containsKey(right)
                ? ChoiceState.MATCHED : ChoiceState.IDLE;
    }

    public Optional<L> selectedLeft() {
        return Optional.ofNullable(selectedLeft);
    }

    public Optional<R> selectedRight() {
        return Optional.ofNullable(selectedRight);
    }

    public Optional<Match<L, R>> leftMatch(L left) {
        return Optional.ofNullable(leftMatches.get(left));
    }

    public Optional<Match<L, R>> rightMatch(R right) {
        return Optional.ofNullable(rightMatches.get(right));
    }

    public List<Match<L, R>> matches() {
        return List.copyOf(leftMatches.values());
    }

    private void pair(L left, R right) {
        unpairLeft(left);
        unpairRight(right);
        Match<L, R> match = new Match<>(left, right, ++nextMatchNumber);
        leftMatches.put(left, match);
        rightMatches.put(right, match);
    }

    private void unpairLeft(L left) {
        Match<L, R> previous = leftMatches.remove(left);
        if (previous != null) {
            rightMatches.remove(previous.right());
        }
    }

    private void unpairRight(R right) {
        Match<L, R> previous = rightMatches.remove(right);
        if (previous != null) {
            leftMatches.remove(previous.left());
        }
    }
}
