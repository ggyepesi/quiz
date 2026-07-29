package quiz.round;

/**
 * Pure progress state for a finite sequence of quiz rounds.
 *
 * <p>The current round is one-based for presentation. Advancing the final
 * round completes the sequence; callers may also complete early when dynamic
 * round generation finds no more playable content.
 */
public final class RoundProgress {
    private final int totalRounds;
    private int currentRound;
    private boolean complete;

    public RoundProgress(int totalRounds) {
        if (totalRounds < 0) {
            throw new IllegalArgumentException(
                    "Total round count cannot be negative");
        }
        this.totalRounds = totalRounds;
        currentRound = totalRounds == 0 ? 0 : 1;
        complete = totalRounds == 0;
    }

    public int totalRounds() {
        return totalRounds;
    }

    public int currentRound() {
        return currentRound;
    }

    public int currentIndex() {
        if (complete) {
            throw new IllegalStateException("Completed rounds have no current index");
        }
        return currentRound - 1;
    }

    public boolean isLastRound() {
        return !complete && currentRound == totalRounds;
    }

    public boolean isComplete() {
        return complete;
    }

    /**
     * Moves to the next round, or completes when called on the final round.
     *
     * @return true when another round became current
     */
    public boolean advance() {
        if (complete) {
            return false;
        }
        if (currentRound >= totalRounds) {
            complete = true;
            return false;
        }
        currentRound++;
        return true;
    }

    public void complete() {
        complete = true;
    }

    public Snapshot snapshot() {
        return new Snapshot(currentRound, totalRounds, complete);
    }

    public record Snapshot(
            int currentRound,
            int totalRounds,
            boolean complete) {

        public Snapshot {
            if (totalRounds < 0
                    || currentRound < 0
                    || currentRound > totalRounds
                    || (!complete && currentRound == 0)) {
                throw new IllegalArgumentException("Invalid round progress");
            }
        }

        public boolean isLastRound() {
            return !complete && currentRound == totalRounds;
        }
    }
}
