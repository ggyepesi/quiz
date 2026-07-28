package process;

/** Reusable unit of work with a plan that cannot change during a run. */
public interface Process<R> {
    ProcessPlan plan();
    ProcessOutcome<R> execute(ProcessContext context) throws Exception;
}
