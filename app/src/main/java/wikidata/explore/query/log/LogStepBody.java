package wikidata.explore.query.log;

@FunctionalInterface
public interface LogStepBody<T> {
    T run(LogStep step) throws Exception;
}
