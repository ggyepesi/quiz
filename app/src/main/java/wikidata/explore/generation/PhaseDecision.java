package wikidata.explore.generation;

/**
 * Whether a phase runs under one request, and — when it does not — why.
 *
 * <p>A skipped phase with no reason is indistinguishable from a phase somebody forgot.
 * Every decision that is not RUN carries the sentence a reader needs, and the same
 * decisions drive both the explanation and the execution, so the diagram cannot claim
 * one thing while the run does another.
 */
public record PhaseDecision(Status status, String reason) {

    public enum Status {
        /** Required and permitted. */
        RUN,
        /** Unnecessary under this request. */
        SKIP,
        /** Required, and impossible under this request. */
        BLOCKED
    }

    public PhaseDecision {
        if (status == null) throw new IllegalArgumentException("A decision needs a status");
        reason = reason == null ? "" : reason.trim();
        if (status != Status.RUN && reason.isEmpty()) {
            throw new IllegalArgumentException(
                    status + " needs a reason a reader can act on");
        }
        if (status == Status.RUN && !reason.isEmpty()) {
            throw new IllegalArgumentException(
                    "A phase that runs explains itself by running: " + reason);
        }
    }

    public static PhaseDecision run() {
        return new PhaseDecision(Status.RUN, "");
    }

    public static PhaseDecision skip(String reason) {
        return new PhaseDecision(Status.SKIP, reason);
    }

    public static PhaseDecision blocked(String reason) {
        return new PhaseDecision(Status.BLOCKED, reason);
    }

    public boolean runs() {
        return status == Status.RUN;
    }

    @Override public String toString() {
        return status + (reason.isEmpty() ? "" : " — " + reason);
    }
}
