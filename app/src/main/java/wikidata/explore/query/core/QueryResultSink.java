package wikidata.explore.query.core;

public interface QueryResultSink<R> {
    void accept(R result) throws Exception;
}