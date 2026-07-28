package process;

import java.util.Objects;
import java.util.Optional;

/** Immutable terminal result. PARTIAL deliberately carries the useful result. */
public record ProcessOutcome<R>(
        ProcessStatus status,
        R result,
        Throwable error,
        String summary) {

    public ProcessOutcome {
        Objects.requireNonNull(status, "status");
        if (!status.terminal()) {
            throw new IllegalArgumentException("An outcome must be terminal");
        }
        summary = summary == null ? "" : summary;
    }

    public static <R> ProcessOutcome<R> succeeded(R result, String summary) {
        return new ProcessOutcome<>(ProcessStatus.SUCCEEDED, result, null, summary);
    }

    public static <R> ProcessOutcome<R> partial(R result, Throwable error, String summary) {
        return new ProcessOutcome<>(ProcessStatus.PARTIAL, result, error, summary);
    }

    public static <R> ProcessOutcome<R> failed(Throwable error) {
        return new ProcessOutcome<>(ProcessStatus.FAILED, null,
                Objects.requireNonNull(error, "error"), error.getMessage());
    }

    public static <R> ProcessOutcome<R> cancelled(R result, String summary) {
        return new ProcessOutcome<>(ProcessStatus.CANCELLED, result, null, summary);
    }

    public Optional<R> usefulResult() {
        return Optional.ofNullable(result);
    }
}
