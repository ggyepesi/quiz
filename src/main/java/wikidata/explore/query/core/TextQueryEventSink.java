package wikidata.explore.query.core;

public interface TextQueryEventSink extends QueryEventSink {
    void text(String text);
}