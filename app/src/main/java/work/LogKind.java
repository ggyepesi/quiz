package work;

public enum LogKind {

    WORKFLOW("Workflow"),
    QUERY("Query"),
    /** One attempt at an endpoint, as its client reports it — distinct from the work
     *  (a query step) it serves, which may make several attempts (#397). */
    REQUEST("Request"),
    MESSAGE("Message");

    private final String label;

    LogKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
