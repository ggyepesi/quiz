package wikidata.explore.query.sink;

import wikidata.explore.query.core.QueryEvent;
import wikidata.explore.query.core.QueryEventSink;
import wikidata.explore.query.core.QueryStatus;

public class ConsoleQueryEventSink implements QueryEventSink {

    @Override
    public void accept(QueryEvent e) {
        if (e.status() == QueryStatus.RUNNING) {
            System.out.println("[Q" + e.id() + "] START " + e.purpose());
        } else {
            System.out.println("[Q" + e.id() + "] "
                                       + e.status()
                                       + " rows=" + e.rowCount()
                                       + " timeMs=" + e.timeMs()
                                       + (e.error().isBlank() ? "" : " error=" + e.error()));
        }
    }
}