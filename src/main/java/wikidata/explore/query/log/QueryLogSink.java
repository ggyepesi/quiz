package wikidata.explore.query.log;

import wikidata.explore.query.core.QueryEvent;
import wikidata.explore.query.core.QueryStatus;
import wikidata.explore.query.core.TextQueryEventSink;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds one {@link QueryLog} per query from the event stream, and folds
 * the query's text-channel output (the rendered SPARQL) into that log.
 *
 * Events and text arrive on the background worker thread while {@link
 * #logs()} is read on the EDT, so all access is guarded by {@code lock}
 * and {@code logs()} returns a snapshot.
 */

public class QueryLogSink implements TextQueryEventSink {

    public interface Listener {
        void logChanged(QueryLog log, boolean added);
    }

    private final Object lock = new Object();
    private final Map<Long, QueryLog> logsById =
            new LinkedHashMap<>();

    private long currentId = -1;
    private long infoId = -1;

    private volatile Listener listener;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void accept(QueryEvent event) {
        if (event == null) {
            return;
        }

        QueryLog log;
        boolean added;

        synchronized (lock) {
            added = !logsById.containsKey(event.id());

            log = logsById.computeIfAbsent(
                    event.id(),
                    id -> new QueryLog(id, event.query()));

            if (event.status() == QueryStatus.RUNNING) {
                currentId = event.id();
            }

            log.update(
                    event.status(),
                    event.rowCount(),
                    event.timeMs(),
                    event.error());

            if (event.status() != QueryStatus.RUNNING
                    && currentId == event.id()) {
                currentId = -1;
            }
        }

        fire(log, added);
    }

    @Override
    public void text(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        QueryLog log;
        boolean added = false;

        synchronized (lock) {
            if (currentId >= 0) {
                log = logsById.get(currentId);
                if (log != null) {
                    log.appendRequestText(text);
                }
            } else {
                infoId--;
                log = QueryLog.info(text);
                logsById.put(infoId, log);
                added = true;
            }
        }

        if (log != null) {
            fire(log, added);
        }
    }

    private void fire(QueryLog log, boolean added) {
        Listener l = listener;
        if (l != null) {
            l.logChanged(log, added);
        }
    }

    public Collection<QueryLog> logs() {
        synchronized (lock) {
            return new ArrayList<>(logsById.values());
        }
    }

    public void clear() {
        synchronized (lock) {
            logsById.clear();
            currentId = -1;
            infoId = -1;
        }
    }
}