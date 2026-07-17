package wikidata.explore.query.log;

public enum LogKind {

    WORKFLOW("Workflow"),
    QUERY("Query"),
    MESSAGE("Message");

    private final String label;

    LogKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
