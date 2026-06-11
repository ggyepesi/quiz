package wikidata.explore.query.core;

public interface QueryEventSink {
    void accept(QueryEvent event);
}