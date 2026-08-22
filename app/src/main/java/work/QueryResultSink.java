package work;

public interface QueryResultSink<R> {
    void accept(R result) throws Exception;
}