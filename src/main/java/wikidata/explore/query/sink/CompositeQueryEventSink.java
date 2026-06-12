package wikidata.explore.query.sink;

import wikidata.explore.query.core.QueryEvent;
import wikidata.explore.query.core.QueryEventSink;
import wikidata.explore.query.core.TextQueryEventSink;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class CompositeQueryEventSink implements TextQueryEventSink {

    private final List<QueryEventSink> sinks =
            new CopyOnWriteArrayList<>();

    public void add(QueryEventSink sink) {
        if (sink != null && !sinks.contains(sink)) {
            sinks.add(sink);
        }
    }

    @Override
    public void accept(QueryEvent event) {
        for (QueryEventSink sink : sinks) {
            sink.accept(event);
        }
    }

    @Override
    public void text(String text) {
        for (QueryEventSink sink : sinks) {
            if (sink instanceof TextQueryEventSink t) {
                t.text(text);
            }
        }
    }
}