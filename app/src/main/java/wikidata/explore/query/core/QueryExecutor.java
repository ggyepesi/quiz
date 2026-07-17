package wikidata.explore.query.core;

public interface QueryExecutor<R> {
    QueryKind kind();

    R execute(QueryDescriptor<R> descriptor) throws Exception;

    default void cancel() {}
}