package work;

@FunctionalInterface
public interface LogStepBody<T> {
    T run(LogStep step) throws Exception;
}
