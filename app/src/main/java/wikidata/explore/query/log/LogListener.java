package wikidata.explore.query.log;

public interface LogListener {

    /**
     * Notified whenever a workflow's log tree changes. {@code added} is
     * true only on the first notification for a given root, so the view
     * can register the workflow once and upsert thereafter.
     */
    void logChanged(LogNode root, boolean added);
}
